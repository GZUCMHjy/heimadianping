-- 申明哪些变量是KEY属性，哪些是VALUES属性
local key = KEYS[1];
local threadId = ARGV[1];
local releaseTime = ARGV[2];
if(redis.call('HEXISTS',key,threadId) == 0) then
    return nil;
end;
-- 是自己的锁，则重入次数先进行减一操作
local count = redis.call('HINCRBY',key,threadId,-1);
-- 判断是否为0
if(count > 0) then
    -- 大于0说明还不能释放锁，重置有效期返回
    redis.call('EXPIRE',key,releaseTime);
    return nil;
else
    redis.call('DEL',key);
    return nil;
end;