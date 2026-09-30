package com.macro.mall.security.util;

import cn.hutool.core.date.DateUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.jwt.JWTUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.userdetails.UserDetails;

import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

/**
 * JwtToken生成的工具类
 * JWT token的格式：header.payload.signature
 * header的格式（算法、token的类型）：
 * {"alg": "HS256","typ": "JWT"}
 * payload的格式（用户名、创建时间、过期时间）：
 * {"sub":"wang","created":1489079981393,"exp":1489684781}
 * signature的生成算法：
 * HMACSHA256(base64UrlEncode(header) + "." +base64UrlEncode(payload),secret)
 *
 * 注意：算法是 HS256 而不是 HS512 —— 这里用的是 Hutool 的
 * {@code JWTUtil.createToken(payload, key)}，它的文档明确写的是 HS256(HmacSHA256)。
 * 原注释写的 HS512 与实际不符，已按实际改正。
 *
 * exp 的单位是「秒」（RFC 7519 规定 NumericDate 为 Unix 秒），
 * 不是毫秒 —— 详见 generateToken / isTokenExpired。
 *
 * Created by macro on 2018/4/26.
 * Refactored to use Hutool JWTUtil
 */
public class JwtTokenUtil {
    private static final Logger LOGGER = LoggerFactory.getLogger(JwtTokenUtil.class);
    private static final String CLAIM_KEY_USERNAME = "sub";
    private static final String CLAIM_KEY_CREATED = "created";
    /** RFC 7519 规定 exp 是「秒级」时间戳，这里显式声明，避免有人再按毫秒用 */
    private static final String CLAIM_KEY_EXPIRED = "exp";
    @Value("${jwt.secret}")
    private String secret;
    @Value("${jwt.expiration}")
    private Long expiration;
    @Value("${jwt.tokenHead}")
    private String tokenHead;

    /**
     * 获取签名密钥
     */
    private byte[] getSigningKey() {
        return secret.getBytes(StandardCharsets.UTF_8);
    }

    /**
     * 根据负责生成JWT的token
     *
     * exp 用「秒」：RFC 7519 的 NumericDate 定义为「自 1970-01-01T00:00:00Z 起的秒数」。
     * 原实现用的是毫秒，虽然自己签自己验能对上，但：
     *   ① 其它语言的 JWT 库（java-jwt、jjwt、pyjwt 等）都按秒解析，跨语言调用会直接判成"已过期"或"2148 年才过期"；
     *   ② 用标准库或第三方工具校验时结果不可预期。
     */
    private String generateToken(Map<String, Object> claims) {
        // 设置过期时间（秒）
        long expireTimeSeconds = System.currentTimeMillis() / 1000 + expiration;
        claims.put(CLAIM_KEY_EXPIRED, expireTimeSeconds);
        return JWTUtil.createToken(claims, getSigningKey());
    }

    /**
     * 从token中获取JWT中的负载
     */
    private Map<String, Object> getPayloadFromToken(String token) {
        try {
            // 验证token签名
            if (!JWTUtil.verify(token, getSigningKey())) {
                LOGGER.info("JWT签名验证失败:{}", token);
                return null;
            }
            // 解析token payload
            return JWTUtil.parseToken(token).getPayloads();
        } catch (Exception e) {
            LOGGER.info("JWT格式验证失败:{}", token);
            return null;
        }
    }

    /**
     * 从token中获取登录用户名
     */
    public String getUserNameFromToken(String token) {
        String username;
        try {
            Map<String, Object> payload = getPayloadFromToken(token);
            username = payload != null ? (String) payload.get(CLAIM_KEY_USERNAME) : null;
        } catch (Exception e) {
            username = null;
        }
        return username;
    }

    /**
     * 验证token是否还有效
     *
     * @param token       客户端传入的token
     * @param userDetails 从数据库中查询出来的用户信息
     */
    public boolean validateToken(String token, UserDetails userDetails) {
        String username = getUserNameFromToken(token);
        return username != null && username.equals(userDetails.getUsername())
                && !isTokenExpired(token)
                && userDetails.isEnabled();
    }

    /**
     * 判断token是否已经失效
     *
     * exp 是「秒级」时间戳，所以这里也必须用秒去比 —— 原来拿毫秒和毫秒比虽然能跑通，
     * 但一旦 exp 按 RFC 改成秒（或与别的库互操作），毫秒比较会永远判成"没过期"。
     */
    private boolean isTokenExpired(String token) {
        try {
            // 手动检查 exp 字段判断是否过期
            Map<String, Object> payload = getPayloadFromToken(token);
            if (payload == null) {
                return true;
            }
            Object exp = payload.get(CLAIM_KEY_EXPIRED);
            if (exp == null) {
                return false;
            }
            long expTimeSeconds = exp instanceof Long ? (Long) exp : ((Number) exp).longValue();
            return expTimeSeconds < System.currentTimeMillis() / 1000;
        } catch (Exception e) {
            return true;
        }
    }

    /**
     * 从token中获取过期时间
     */
    private Date getExpiredDateFromToken(String token) {
        Map<String, Object> payload = getPayloadFromToken(token);
        if (payload == null) {
            return null;
        }
        Object exp = payload.get(CLAIM_KEY_EXPIRED);
        if (exp instanceof Number) {
            // exp 是秒，Date 需要毫秒，这里换算
            return new Date(((Number) exp).longValue() * 1000);
        }
        return null;
    }

    /**
     * 根据用户信息生成token
     */
    public String generateToken(UserDetails userDetails) {
        Map<String, Object> claims = new HashMap<>();
        claims.put(CLAIM_KEY_USERNAME, userDetails.getUsername());
        claims.put(CLAIM_KEY_CREATED, new Date());
        return generateToken(claims);
    }

    /**
     * 当原来的token没过期时是可以刷新的
     *
     * @param oldToken 带tokenHead的token
     */
    public String refreshHeadToken(String oldToken) {
        if (StrUtil.isEmpty(oldToken)) {
            return null;
        }
        String token = oldToken.substring(tokenHead.length());
        if (StrUtil.isEmpty(token)) {
            return null;
        }
        // token校验不通过
        Map<String, Object> payload = getPayloadFromToken(token);
        if (payload == null) {
            return null;
        }
        // 如果token已经过期，不支持刷新
        if (isTokenExpired(token)) {
            return null;
        }
        // 如果token在30分钟之内刚刷新过，返回原token
        if (tokenRefreshJustBefore(token, 30 * 60)) {
            return token;
        } else {
            payload.put(CLAIM_KEY_CREATED, new Date());
            return generateToken(payload);
        }
    }

    /**
     * 判断token在指定时间内是否刚刚刷新过
     *
     * @param token 原token
     * @param time  指定时间（秒）
     */
    private boolean tokenRefreshJustBefore(String token, int time) {
        Map<String, Object> payload = getPayloadFromToken(token);
        if (payload == null) {
            return false;
        }
        Object created = payload.get(CLAIM_KEY_CREATED);
        Date createdDate = null;
        if (created instanceof Long) {
            createdDate = new Date((Long) created);
        } else if (created instanceof Date) {
            createdDate = (Date) created;
        }
        if (createdDate == null) {
            return false;
        }
        Date refreshDate = new Date();
        // 刷新时间在创建时间的指定时间内
        return refreshDate.after(createdDate) && refreshDate.before(DateUtil.offsetSecond(createdDate, time));
    }
}
