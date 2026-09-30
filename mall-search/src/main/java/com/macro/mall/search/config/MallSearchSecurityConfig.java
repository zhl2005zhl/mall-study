package com.macro.mall.search.config;

import cn.hutool.core.collection.CollUtil;
import com.macro.mall.mapper.UmsAdminMapper;
import com.macro.mall.model.UmsAdmin;
import com.macro.mall.model.UmsAdminExample;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

import java.util.Collections;
import java.util.List;

/**
 * mall-search 的安全配置。
 *
 * 【为什么会有这个类】
 * mall-search 原来是 7 个模块里唯一不依赖 mall-security 的，于是 EsProductController
 * 下的 8 个接口全部可以匿名访问 —— 其中 4 个是索引维护操作：
 *   · POST /esProduct/importAll      触发全量导入 ES（重操作，可被当 DoS 入口）
 *   · POST /esProduct/create/{id}    写入 / 覆盖索引文档
 *   · GET  /esProduct/delete/{id}    把商品从索引里删掉（该商品在搜索中直接消失）
 *   · POST /esProduct/delete/batch   批量删除
 *
 * 根因不是"忘了加鉴权注解"，而是职责边界：这类运维操作本该属于后台管理，
 * 却被放在搜索服务自己的 Controller 里，于是自然继承了"这个模块没有安全模块"的特性。
 *
 * 【这次采用的方案】
 * 引入 mall-security，配置白名单只放行查询接口（见 application.yml 的 secure.ignored.urls），
 * 维护接口则要求登录。JWT 密钥与 mall-admin 保持一致，所以后台登录拿到的 token
 * 可以直接用来调索引维护接口，不需要再登录一次。
 *
 * 【更彻底的做法（台账里的方案 A）】
 * 把 4 个维护接口整体迁到 mall-admin，纳入它已经有的动态权限体系（能精确到
 * "哪个角色能调 importAll"），让 mall-search 变成一个纯只读服务。
 * 这里没直接做，是因为 mall-admin 目前不依赖 ES，迁移需要给它补上 ES 客户端，
 * 属于模块职责的重构，不适合和本次缺陷修复混在一起。
 *
 * Created 2026/09/30.
 */
@Configuration
public class MallSearchSecurityConfig {

    @Autowired
    private UmsAdminMapper adminMapper;

    /**
     * 提供 UserDetailsService 给 JwtAuthenticationTokenFilter 用。
     *
     * 注意这里没有提供 dynamicSecurityService —— 那意味着 mall-search 不做
     * 细粒度的动态权限判定（mall-security 的 SecurityConfig 在拿不到
     * DynamicAuthorizationManager 时会退化成"只要登录就行"），
     * 这正是本次想要的粒度：搜索服务只需要区分「匿名」和「已登录」。
     */
    @Bean
    public UserDetailsService userDetailsService() {
        return username -> {
            UmsAdminExample example = new UmsAdminExample();
            example.createCriteria().andUsernameEqualTo(username);
            List<UmsAdmin> adminList = adminMapper.selectByExample(example);
            if (CollUtil.isEmpty(adminList)) {
                // 提示与 mall-admin 的登录失败提示保持一致，避免成为用户名枚举接口
                throw new UsernameNotFoundException("用户名或密码错误");
            }
            UmsAdmin admin = adminList.get(0);
            // 权限列表留空：mall-search 不做细粒度授权，只要求「已认证」
            return new User(admin.getUsername(), admin.getPassword(), Collections.emptyList());
        };
    }
}
