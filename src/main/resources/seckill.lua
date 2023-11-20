-- 1. 参数列表
-- 1.1 优惠券id
local voucherId = ARGV[1]
-- 1.2 用户id
local userId = ARGV[2]

-- 2. 数据key
-- 2.1 库存key
local stockKey = "seckill:stock:" .. voucherId
-- 2.2 订单key
local orderKey = "seckill:order:" .. userId

-- 3. 脚本业务
-- 3.1 判断库存是否充足
if(tonumber(redid.call('get',stockKey)) < 0) then
    return 1
end

-- 3.2 判断用户是否已经下单
if(redis.call('sismember',orderKey,voucherId) == 1) then
    return 2
end

-- 扣减库存
redis.call('incrby',stock,-1)
-- 记录已经下过单
redis.call('add',orderKey,userId)

return 0















