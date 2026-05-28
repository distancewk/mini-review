package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RegexUtils;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.BitFieldSubCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import javax.servlet.http.HttpSession;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.LOGIN_CODE_KEY;
import static com.hmdp.utils.RedisConstants.LOGIN_USER_KEY;

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
    private StringRedisTemplate  stringRedisTemplate;

    @Override
    public Result sendCode(String phone, HttpSession session) {
        if(RegexUtils.isPhoneInvalid(phone)){
            return Result.fail("手机号格式错误");
        }
        String code = RandomUtil.randomNumbers(6);
        stringRedisTemplate.opsForValue().set(LOGIN_CODE_KEY +phone,code,2, TimeUnit.MINUTES);

        log.debug("发送短信验证码成功，验证码：{}",code);
        return Result.ok("发送成功");
    }

    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {
        if(RegexUtils.isPhoneInvalid(loginForm.getPhone())){
            return Result.fail("手机号格式错误");
        }
        String code = stringRedisTemplate.opsForValue().get(LOGIN_CODE_KEY +loginForm.getPhone());
        String code1 = loginForm.getCode();
        if(code == null || !code.equals(code1)){
            return Result.fail("验证码错误");
        }
        User user = query().eq("phone", loginForm.getPhone()).one();
        if (user == null){
            user = createUserWithPhone(loginForm.getPhone());
        }
        String token = UUID.randomUUID().toString(true);
        UserDTO userDTO =BeanUtil.copyProperties(user,UserDTO.class);

        Map<String, Object> stringObjectMap = BeanUtil.beanToMap(userDTO,new HashMap<>(),
                CopyOptions.create().setIgnoreNullValue(true)
                        .setFieldValueEditor((fieldName,fieldValue) -> fieldValue.toString()));
        stringRedisTemplate.opsForHash().putAll(LOGIN_USER_KEY+token,stringObjectMap);
        stringRedisTemplate.expire(LOGIN_USER_KEY+token,RedisConstants.LOGIN_USER_TTL,TimeUnit.MINUTES);
        log.info("登录成功，userId: {}", userDTO.getId());
        return Result.ok(token);
    }

    @Override
    public Result logout(String token) {
        if (token != null && !token.trim().isEmpty()) {
            stringRedisTemplate.delete(LOGIN_USER_KEY + token);
        }
        UserHolder.removeUser();
        return Result.ok();
    }

    @Override
    public Result sign() {
        //1.获取当前登录用户
        Long id = UserHolder.getUser().getId();

        //2.获取日期
        LocalDateTime now = LocalDateTime.now();
        //3.拼接key
        String format = now.format(DateTimeFormatter.ofPattern("yyyy:MM"));
        String key = RedisConstants.USER_SIGN_KEY + id + format;

        //4。获取今天是这个月的第几天
        int day = now.getDayOfMonth();

        //5.写入Redis
        stringRedisTemplate.opsForValue().setBit(key,day-1  ,true);
        return Result.ok();
    }

    @Override
    public Result signCount() {
        //1.获取当前登录用户
        Long id = UserHolder.getUser().getId();

        //2.获取日期
        LocalDateTime now = LocalDateTime.now();
        //3.拼接key
        String format = now.format(DateTimeFormatter.ofPattern("yyyy:MM"));
        String key = RedisConstants.USER_SIGN_KEY + id + format;
        //4。获取今天是这个月的第几天
        int day = now.getDayOfMonth();
        //5.获取本月截至今天为止所有的签到记录，返回10进制的数字
        List<Long> longs = stringRedisTemplate.opsForValue()
                .bitField(key,
                        BitFieldSubCommands.create()
                                .get(BitFieldSubCommands.BitFieldType.unsigned(day))
                                .valueAt(0));
        if(longs == null || longs.size() == 0){
            //没有签到
            return Result.ok(0);
        }
        Long signCount = longs.get(0);
        if(signCount == null || signCount == 0){
            return Result.ok(0);
        }

        //6.循环遍历
        int count = 0;
        while(true){
            //7.让这个数字与1做与运算，得到数字的最后一个bit位
            if ((signCount & 1) ==0){
                //判断这个bit位是否为0
                break;
        }else {
                //如果为0，说明未签到，结束
                count++;
            }
            //把数字右移一位,抛弃最后一个bit位，继续下一个bit位
            signCount >>>=1;
        }
        return Result.ok(count);

    }

    private User createUserWithPhone(String phone) {
        User user = new User();
        user.setPhone(phone);
        user.setNickName(SystemConstants.USER_NICK_NAME_PREFIX +RandomUtil.randomString(10));
        save(user);
        return user;
    }
}
