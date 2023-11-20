package com.hmdp.config;


import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * @author louis
 * @version 1.0
 * @date 2023/11/18 14:44
 */
@Configuration
public class RedisConfig {
    @Bean
    public RedissonClient redissonClient(){
        // 1. 创建配置类
        Config config = new Config();
        // 2. (配置)添加redis地址，这里添加单点的地址，也可以使用config.useClusterServers()添加集群地址
        config.useSingleServer().setAddress("redis://42.193.201.114:6379").setDatabase(0);
        // 3. （实例）创建客户端
        return Redisson.create(config);
    }
}
