package com.hmdp.utils;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/**
 * @author louis
 * @version 1.0
 * @date 2023/10/22 9:33
 */
// 用于用户登录信息的校验
public class LoginInterceptor implements HandlerInterceptor {
    // 无法注入 因为不属于Spring自带的对象
    private StringRedisTemplate stringRedisTemplate;
    public LoginInterceptor (StringRedisTemplate stringRedisTemplate){
        this.stringRedisTemplate = stringRedisTemplate;
    }
    // 这里解释一下 为什么 实现HandlerInterceptor接口 不是全部实现
    // 按理来说 实现接口，必须实现接口里面所有的方法，这里只要实现preHandle和afterCompletion
    // 是因为HandlerInterceptor 里面的方法是默认实现方法了 所以我们就可以自行选择方法进行重写override 不选则默认它规定好的方法执行
    // 所以接口的方法不一定是要全部重写，要看它是否是接口默认的抽象方法，而不是已经实现好的方法！
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        // 判断是否需要拦截（ThreadLocal中是否有用户信息）
        if(UserHolder.getUser() == null){
            response.setStatus(401);
            // 拦截
            return false;
        }
        // 放行
        return true;
    }

}
