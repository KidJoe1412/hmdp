package com.hmdp.utils;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.hmdp.dto.UserDTO;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.LOGIN_USER_KEY;
import static com.hmdp.utils.RedisConstants.LOGIN_USER_TTL;

public class LoginInterceptor implements HandlerInterceptor {

    /*
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        //1. 获取session
        // HttpSession session = request.getSession();
        //1. 获取请求头中的token
        String token = request.getHeader("authorization");
        if (StrUtil.isBlank(token)){
            //4. 不存在，拦截
            response.setStatus(401);
            return false;
        }

        //2. 获取session中的用户
        //Object user = session.getAttribute("user");
        //2. 基于token获取redis中的用户
        String key = LOGIN_USER_KEY + token;
        Map<Object, Object> userMap = stringRedisTemplate.opsForHash().entries(key);
        //3. 判断用户是否存在
        if (userMap.isEmpty()){
            //4. 不存在，拦截
            response.setStatus(401);
            return false;
        }

        //5. 将查询到的hash数据转换为userDto对象
        UserDTO userDTO = BeanUtil.fillBeanWithMap(userMap, new UserDTO(), false);
        //5. 存在 保存用户信息到ThreadLocal
        //UserHolder.saveUser((UserDTO) user);

        //6. 存在 保存用户信息到ThreadLocal
        UserHolder.saveUser(userDTO);

        //7. 刷新token有效期
        stringRedisTemplate.expire(key,LOGIN_USER_TTL, TimeUnit.MINUTES);

        //8. 放行
        return true;
    }
    */
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        //1. 判断是否需要拦截(ThreadLocal中是否有用户)
        if (UserHolder.getUser() == null){
            response.setStatus(401);
            return false;
        }
        return true;
    }
}
