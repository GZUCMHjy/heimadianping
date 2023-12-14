package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RegexUtils;

import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import javax.annotation.Resource;
import javax.servlet.http.HttpSession;


import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;
import static com.hmdp.utils.SystemConstants.USER_NICK_NAME_PREFIX;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
@Slf4j
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;
    /**
     * 发送验证码（登录之前的操作）
     * @param phone
     * @param session
     * @return
     */
    @Override
    public Result sendCode(String phone, HttpSession session) {
        // 1. 校验手机号
        if(RegexUtils.isPhoneInvalid(phone)){
            return Result.fail("手机号格式错误");
        }
        // 2. 生成验证码
        String code = RandomUtil.randomNumbers(6);
        // 3. 保存验证码到session 换成 存（写）入早redis当中
        // key：LOGIN_CODE_KEY+phone
        // value: code
        // 写入redis一定要设置有效期！！！
        // key一般都要加上业务前缀 以示区分
        stringRedisTemplate.opsForValue().set(LOGIN_CODE_KEY + phone,code,LOGIN_CODE_TTL, TimeUnit.MINUTES);
        // 4. 发送验证码
        log.debug("发送短信验证码成功,验证码:{}",code);
        return Result.ok();
    }

    @Override
    public Result login(LoginFormDTO loginFormDTO) {
        // 1. 校验手机号和验证码
        String phone = loginFormDTO.getPhone();
        if(RegexUtils.isPhoneInvalid(phone)){
            return Result.fail("手机号格式错误");
        }
        // 从session中取出用户信息 改成 从redis获取用户信息
        //  Object cache = session.getAttribute("code"); 有setAttribute就有getAttribute
        String cacheCode = stringRedisTemplate.opsForValue().get(LOGIN_CODE_KEY + phone);
        String code = loginFormDTO.getCode();
        if(cacheCode == null || !cacheCode.equals(code)){
            return Result.fail("验证码错误");
        }
        // 2. 根据手机号条件查询对应用户 one()
        User user = query().eq("phone", loginFormDTO.getPhone()).one();
        // 3. 判断用户是否存在
        if(user == null){
            // 4. 不存在创建(注册)新用户
            user = createUserWithPhone(phone);
        }

        // 创建后，存入到session 也可以 先new一个userDTO 然后使用BeanUtils.copyProperties(user,userDTO)
        // spring框架自带的工具BeanUtils
        // 用户信息存入到session 改成 存入到 redis （采用Hash数据结构）
        // session.setAttribute("user", BeanUtil.copyProperties(user, UserDTO.class));
        // token 由UUID生成(随机且唯一)
        String token = UUID.randomUUID().toString();

        // 将User对象转变成HashMap存储到Redis的Hash结构当中
        // 存储力度降低 可以降低内存消耗（取出不需要的信息）同时数据脱敏
        // user -> userDTO （source ——> target）
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
        UserHolder.saveUser(userDTO);
        // 用户信息写入到redis（可能会出现类型转换异常）
        // stringRedisTemplate需要key和value都是String类型 因此需要将其进行数据类型的转换
        Map<String, Object> userMap = BeanUtil.beanToMap(userDTO,new HashMap<>(),
                        CopyOptions.create().
                        setIgnoreNullValue(true).
                        setFieldValueEditor((filedName,fieldValue) -> fieldValue.toString()));
        // putAll 比 put更灵活 减少对Redis交互次数（读写）
        // 用户信息写入到Redis当中去
        String tokenKey = LOGIN_USER_KEY + token ;
        // 相当与存入不同session（key和value都是不同的）
        stringRedisTemplate.opsForHash().putAll(tokenKey,userMap);
        // hash得要单独设置号有效时间
        // session也是有效期是30min
        stringRedisTemplate.expire(tokenKey,LOGIN_USER_TTL,TimeUnit.MINUTES);
        // 返回给前端的token
        return Result.ok(token);
    }


    /**
     * 根据手机号创建用户
     * @param phone
     * @return
     */
    private User createUserWithPhone(String phone){
        User user = new User();
        user.setPhone(phone);
        // 用静态常量 （专业）
        user.setNickName(USER_NICK_NAME_PREFIX + RandomUtil.randomString(10) );
        // 写入数据库 this.save(user) 也可以 意思更明确吧 表示当前save是对User表进行的
        save(user);
        return user;
    }
}
