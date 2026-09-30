package com.macro.mall.common.constant;

/**
 * 缓存 Key 常量
 *
 * 为什么放在 mall-common 而不是各自的模块里：
 * 缓存的「读」和「写」不在同一个模块 —— mall-portal 负责读（回源 + 写缓存），
 * mall-admin 负责写（后台改数据后主动删缓存）。两边必须用同一个 key 才能对上，
 * 所以统一沉淀到公共模块，避免「改了这边忘了那边」导致缓存永久脏读。
 *
 * Created 2026/09/30.
 */
public class CacheKeys {

    /**
     * 首页聚合内容（GET /home/content）
     *
     * 用固定 key 而不是按用户分 key 的原因：content() 的返回值里不含任何用户维度信息
     * （源码中没有调用 getCurrentMember()），所以全体访客可以共用一份缓存。
     */
    public static final String HOME_CONTENT = "mall:home:content";

    /**
     * 商品详情前缀（GET /product/detail/{id}）
     *
     * 这里的情况和首页相反：key 是由 URL 里的 id 拼出来的，**外部可控**。
     * 用不存在的 id 高频请求，就会绕过缓存每次都打到数据库 —— 这就是缓存穿透。
     */
    public static final String PRODUCT_DETAIL_PREFIX = "mall:product:detail:";

    /**
     * 空值标记（用于防缓存穿透）
     *
     * 查不到数据时，往缓存里写这个标记而不是什么都不写。
     * 这样下一次同样的请求会命中「空值」直接返回，不再落到数据库。
     */
    public static final String NULL_MARKER = "__NULL__";

    private CacheKeys() {
    }
}
