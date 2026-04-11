package com.hmdp.utils;

import cn.hutool.core.bean.BeanUtil;
import com.hmdp.dto.UserDTO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Component
public class RefreshTokenInterceptor implements HandlerInterceptor {
    @Autowired
    private StringRedisTemplate stringRedisTemplate;
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        //1.获得请求头中的token
        String token = request.getHeader("authorization");
        if (token == null || token.isEmpty()) {
            return true;
        }

        //2.基于token获得redis中的用户
        String key = "login_token:" + token;
        Map<Object, Object> userMap = stringRedisTemplate.opsForHash().entries(key);
        //3.判断用户是否存在
// ... existing code ...
        if (userMap.isEmpty()) {
            return true;
        }

        UserDTO userDTO = BeanUtil.toBean(userMap, UserDTO.class);

        //4.保存用户信息到ThreadLocal
        UserHolder.saveUser(userDTO);
// ... existing code ...


        //5,刷新token有效期
        stringRedisTemplate.expire(key, 30, TimeUnit.MINUTES);
        return true;
    }
}
