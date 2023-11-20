package com.hmdp.utils;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.toolkit.StringUtils;
import com.hmdp.dto.UserDTO;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.LOGIN_USER_KEY;
import static com.hmdp.utils.RedisConstants.LOGIN_USER_TTL;

/**
 * @author louis
 * @version 1.0
 * @date 2023/10/22 9:33
 */
public class RefreshTokenInterceptor implements HandlerInterceptor {
    // 无法注入 因为不属于Spring自带的对象
    private StringRedisTemplate stringRedisTemplate;
    public RefreshTokenInterceptor(StringRedisTemplate stringRedisTemplate){
        this.stringRedisTemplate = stringRedisTemplate;
    }
    // 这里解释一下 为什么 实现HandlerInterceptor接口 不是全部实现
    // 按理来说 实现接口，必须实现接口里面所有的方法，这里只要实现preHandle和afterCompletion
    // 是因为HandlerInterceptor 里面的方法是默认实现方法了 所以我们就可以自行选择方法进行重写override 不选则默认它规定好的方法执行
    // 所以接口的方法不一定是要全部重写，要看它是否是接口默认的抽象方法，而不是已经实现好的方法！
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        // 1. 获取session 改成获取 请求头的token（由UUID生成的）
        //  HttpSession session = request.getSession();
        String token = request.getHeader("Authorization");
        if(StringUtils.isBlank(token)){
            return true;
        }
        // 2. 查询Redis缓冲是否有用户信息
        String key = LOGIN_USER_KEY + token ;
        Map<Object, Object> userMap = stringRedisTemplate.opsForHash().entries(key);
        // 3. 判断用户是否存在
        if(userMap.isEmpty()){
            return true;
        }
        // 5. 存在 ，保存用户信息到ThreadLocal
        // userMap转回user
        UserDTO userDTO = BeanUtil.fillBeanWithMap(userMap, new UserDTO(), false);
        // 作用是将拦截器获取的用户信息传入响应的controller层去
        UserHolder.saveUser(userDTO);
        // 刷新token的有效期
        stringRedisTemplate.expire(key,LOGIN_USER_TTL, TimeUnit.MINUTES);
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) throws Exception {
        HandlerInterceptor.super.afterCompletion(request, response, handler, ex);
    }
}
