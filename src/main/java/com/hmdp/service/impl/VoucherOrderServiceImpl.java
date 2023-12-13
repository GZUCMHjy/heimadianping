package com.hmdp.service.impl;
import cn.hutool.core.bean.BeanUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;

/**
 * <p>
 *  服务实现类
 * </p>
 */
@Service
@Slf4j
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {


        // 秒杀活动的优惠券单独一个服务（普通优惠券就额外一个服务，这样好进行秒杀，思路更清晰一些）
        @Resource
        private ISeckillVoucherService seckillVoucherService;

        @Resource
        private RedisIdWorker redisIdWorker;

        @Resource
        private StringRedisTemplate stringRedisTemplate;


        // 注入RedissonClient(客户端)代替StringRedisTemplate
        @Resource
        private RedissonClient redissonClient;


        // 申明并创建父线程的线程池，且该线程池中有一个子线程
        private static final ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();

        private static final DefaultRedisScript<Long> SKCKILL_SCRIPT ;

        // 初始化静态代码块，执行秒杀脚本
        static {
            SKCKILL_SCRIPT= new DefaultRedisScript<>();
            SKCKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
            SKCKILL_SCRIPT.setResultType(Long.class);
        }

        /**
         * 初始完成后，然后开启一个子线程（注解@PostConstruct）
         * 实例VoucherOrderHandler对象,负责执行数据库下单操作
         */
        @PostConstruct
        public void init(){
            SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandler());
        }
        private IVoucherOrderService proxy;// 主线程获取的代理

    /**
     * 秒杀业务
     * @param voucherId 优惠券Id
     * @return
     */
       @Override
       public Result addVoucherOrder(Long voucherId) {
            // todo 获取不到用户id NPE
            Long userId = UserHolder.getUser().getId();
            long orderId = redisIdWorker.nextId("order");
            // 脚本功能：校验是否有购买资格 + 同时发确认消息到redis进行认证
            Long result = stringRedisTemplate.execute(
                    SKCKILL_SCRIPT,
                    Collections.emptyList(),
                    voucherId.toString(), userId.toString(), String.valueOf(orderId) //脚本传入三个参数
            );
            // 2. 判断结果（0 / 1）
            int r = result.intValue();
            if (r != 0) {
                // 2.1 不为0 代表没有购买资格
                return Result.fail(r == 1 ? "库存不足" : "不能重复下单");
            }
            // 3. 获取代理对象
            proxy = (IVoucherOrderService) AopContext.currentProxy();
            // 4. 订单id
            return Result.ok(orderId);
        }

    /**
     * 子线程内部类
     * 尝试读取消息队列信息
     */

    public class VoucherOrderHandler implements Runnable {
        // 消息队列 -> 消费组 -> 消费者
        String queueName = "stream.orders";
        @Override
        public void run() {
            while (true) {
                try {
                    // 1. 获取消息队列有无信息
                    // 相当于 XREADGROUP GROUP g1 c1 COUNT 1 BLOCK 2000 STREAMS stream.orders >
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            // 消费组名g1,消费者c1
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),
                            StreamOffset.create(queueName, ReadOffset.lastConsumed())
                    );
                    // 2. 判断消息获取是否成功
                    if (list == null || list.isEmpty()) {
                        // 2.1 获取失败 继续循环一次
                        continue;
                    }
                    // 3. 获取成功 可以下单
                    MapRecord<String, Object, Object> record = list.get(0);
                    Map<Object, Object> values = record.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(values, new VoucherOrder(), true);
                    handleVoucherOrder(voucherOrder);
                    // 4. ACK确认
                    stringRedisTemplate.opsForStream().acknowledge(queueName, "g1", record.getId());
                } catch (Exception e) {
                    log.error("处理订单异常" + e);
                    // 待处理消息的数组（PendingList）
                    handlePendingList();
                }
            }
        }
        private void handlePendingList() {
            while (true) {
                try {
                    // 1. 读取pendingList队列有无信息
                    // 相当于 XREADGROUP GROUP g1 c1 COUNT 1 STREAMS stream.orders >
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1),
                            StreamOffset.create(queueName, ReadOffset.from("0"))
                    );
                    // 2. 判断消息获取是否成功
                    if (list == null || list.isEmpty()) {
                        // 2.1 说明pendinglist没有异常未处理的消息，跳出循环
                        break;
                    }
                    // 3. 获取成功 可以下单
                    MapRecord<String, Object, Object> record = list.get(0);
                    Map<Object, Object> values = record.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(values, new VoucherOrder(), true);
                    handleVoucherOrder(voucherOrder);
                    // 4. ACK确认
                    stringRedisTemplate.opsForStream().acknowledge(queueName, "g1", record.getId());

                } catch (Exception e) {
                    log.error("处理订单异常" + e);
                    try {
                        Thread.sleep(20);
                    } catch (InterruptedException exception) {
                        exception.printStackTrace();
                    }
                }
            }
        }
    }
        /* private BlockingQueue<VoucherOrder>  orderTasks = new ArrayBlockingQueue<>(1024 * 1024);// 阻塞队列
            // 开启子线程执行数据库下单操作
            public class VoucherOrderHandler implements Runnable{
                @Override
                public void run(){
                    while(true){
                        // 1. 获取阻塞队列的订单信息（获取不到，直接阻塞【不用担心while（true）占用cpu资源】，知道有消息就从阻塞队列中获取）
                        try {
                            VoucherOrder voucherOrder = orderTasks.take();
                            handleVoucherOrder(voucherOrder);
                        } catch (Exception e) {
                            throw new RuntimeException(e);
                        }
                    }
                }
            }*/

        /**
         * 异步线程里的方法不需要返回结果（直接执行完成即可）
         * 其实这里不用加锁，为了一个兜底方案
         * @param voucherOrder 优惠券订单
         */
        private void handleVoucherOrder(VoucherOrder voucherOrder) {
            // 1. 获取用户
            Long userId = voucherOrder.getUserId();
            // 2. 获取锁
            RLock lock = redissonClient.getLock("lock:order" + userId);
            // 3. 尝试抢到锁，将线程信息写入到Redis中
            // 失败就立马返回false
            boolean isLock = lock.tryLock();
            if (!isLock) {
                return;
            }
            try {
                // 通过父类线程的代理在非事务方法中调用事务方法，也是异步线程的方法
                proxy.createVoucherOrder(voucherOrder);
            } finally {
                // unlock保证原子性
                lock.unlock();
            }
        }

        /**
         * 秒杀下单优惠券
         *
         * @param voucherId
         * @return
         */
        //    @Override
        //    public Result addVoucherOrder(Long voucherId) {
        //
        //        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
        //        // 判断秒杀活动是否开始
        //        if(voucher.getBeginTime().isAfter(LocalDateTime.now())){
        //            // 未开始
        //            return Result.fail("活动尚未开始");
        //        }
        //        if(voucher.getEndTime().isBefore(LocalDateTime.now())){
        //            // 已结束
        //            return Result.fail("活动已经结束");
        //        }
        //        // 判断该优惠券是否充足
        //        if(voucher.getStock() < 1){
        //            // 不充足
        //            return Result.fail("已被抢空");
        //        }
        //        Long userId = UserHolder.getUser().getId();
        //        // 可以实现先获取锁 然后在释放锁 这样才不会出现并发问题
        //        // 悲观锁 该方法在集群条件下，不安全
        //        //  synchronized (userId.toString().intern()){
        //        //   获取事务的代理对象
        //        //  IVoucherOrderService proxy = (IVoucherOrderService)AopContext.currentProxy();
        //        //  还需要额外pom.xml文件和启动文件加上注解
        //        //  return proxy.createVoucherOrder(voucherId);
        //        // }
        //        // 1. 获取锁
        //        // SimpleRedisLock lock = new SimpleRedisLock(stringRedisTemplate,"order" + userId);
        //        RLock lock = redissonClient.getLock("lock:order" + userId);
        //        // 2. 尝试抢到锁，将线程信息写入到Redis中
        //        // 失败就立马返回false
        //        boolean isLock = lock.tryLock();
        //        if(!isLock){
        //            return Result.fail("不允许重复下单");
        //        }
        //        try{
        //            IVoucherOrderService proxy = (IVoucherOrderService)AopContext.currentProxy();
        //            // 还需要额外pom.xml文件和启动文件加上注解
        //            return proxy.createVoucherOrder(voucherId);
        //        }finally{
        //            // unlock保证原子性
        //            lock.unlock();
        //        }
        //    }
        //


        //    /**
//     * 秒杀下单优惠券（阻塞队列版）
//     * @param voucherId
//     * @return
//     */
//    @Override
//    public Result addVoucherOrder(Long voucherId) {
//        // 1. 执行lua脚本(有无购买资格)
//        // todo 获取不到用户id NPE
//        Long userId = UserHolder.getUser().getId();
//        Long result = stringRedisTemplate.execute(
//                SKCKILL_SCRIPT,
//                Collections.emptyList(),
//                voucherId.toString(), userId.toString()
//        );
//        // 2. 判断结果（0 / 1）
//        int r = result.intValue();
//        if(r != 0){
//            // 2.1 不为0 代表没有购买资格
//            return Result.fail(r == 1 ? "库存不足" : "不能重复下单");
//        }
//
//        // 2.2 为0 代表没有购买资格
//        VoucherOrder voucherOrder = new VoucherOrder();
//        // 2.3 订单id
//        long orderId = redisIdWorker.nextId("order");
//        // 2.4 用户id
//        voucherOrder.setId(orderId);
//        // 2.5 代金券id
//        voucherOrder.setVoucherId(voucherId);
//        // 2.6 放到阻塞队列
//        orderTasks.add(voucherOrder);
//        // 3. 获取代理对象
//        proxy = (IVoucherOrderService)AopContext.currentProxy();
//        // 4. 订单id
//        return Result.ok(orderId);
//    }

        /**
         * 方法上面加上sycnchronized 锁的范围太大了，会影响性能，所以需要将其锁在方法内部
         * @param
         * @return
         */
        //    @Transactional // 修改、删除、添加等操作需要加上事务
        //    public  Result createVoucherOrder(Long voucherId){
        //        // 一人一单（加锁）
        //        Long userId = UserHolder.getUser().getId();
        //        // 查询数据库
        //        Integer count = query()
        //                        .eq("user_id", userId)
        //                        .eq("voucher_id", voucherId).count();
        //            if(count >= 1){
        //                return Result.fail("已经购买过了");
        //            }
        //            // 开始扣库存
        //            boolean success = seckillVoucherService.update().setSql("stock = stock - 1") // set条件 set stock = stock - 1
        //                    .eq("voucher_id", voucherId)  // where条件
        //                    .gt("stock",0).update(); // stock > 0
        //            if(!success){
        //                // 不成功
        //                return Result.fail("抢购失败,库存不够");
        //            }
        //
        //            VoucherOrder voucherOrder = new VoucherOrder();
        //            long orderId = redisIdWorker.nextId("order");
        //            voucherOrder.setId(orderId);
        //            voucherOrder.setVoucherId(voucherId);
        //            // 保存到数据库
        //            save(voucherOrder);
        //            return Result.ok(orderId);
        //    }

        @Transactional // 修改、删除、添加等操作需要加上事务
        @Override // 不写该注解 编译器也能识别的到
        public void createVoucherOrder(VoucherOrder voucherOrder) {
            // 一人一单（加锁）
            // 子线程（通过参数进行获取）而不是UserHolder
            Long userId = voucherOrder.getUserId();
            // 查询数据库
            Integer count = query()
                    .eq("user_id", userId)
                    .eq("voucher_id", voucherOrder.getVoucherId()).count();
            if (count >= 1) {
                log.info("已经购买过了");
                return;
            }
            // 开始扣库存
            boolean success = seckillVoucherService.update().setSql("stock = stock - 1") // set条件 set stock = stock - 1
                    .eq("voucher_id", voucherOrder.getVoucherId())  // where条件
                    .gt("stock", 0).update(); // stock > 0
            if (!success) {
                // 不成功
                log.info("库存不够，扣件失败");
                return;
            }
            // 保存到数据库
            save(voucherOrder);
        }
}
