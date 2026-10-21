package com.zlf.datapilot.config;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;

/**
 * 最小鉴权（Ticket 14）：三个角色 + HTTP Basic，只保护<b>写</b>接口。
 *
 * <h2>为什么只保护写</h2>
 * 问数（{@code POST /api/ask}）、trace 查询、元数据/指标/闸门的<b>读</b>接口全部匿名放行。
 * 理由不是「省事」，而是这两类操作的后果不同：
 * <ul>
 *   <li>读 + 问数走的是只读账号和五道闸门，最坏结果是「查到一个不该看的数」——靠的是<b>数据侧</b>权限；</li>
 *   <li>写接口能改 {@code metric_sql} 与闸门阈值。{@code metric_sql} 是喂给 LLM 的口径定义，
 *       改它等于绕过五道闸门直接决定「什么 SQL 算合法」（审计 P0#2）；改闸门阈值等于自己给自己放宽边界。
 *       这两件事必须是「有人负责」的动作，所以必须有身份。</li>
 * </ul>
 *
 * <h2>三角色分工</h2>
 * <table border="1">
 *   <tr><th>角色</th><th>能写什么</th><th>为什么这么分</th></tr>
 *   <tr><td>{@code BUSINESS}（业务）</td><td>什么都不行</td><td>业务方只问数、看结果；口径与阈值不是他定的</td></tr>
 *   <tr><td>{@code ADMIN}（管理员）</td><td>指标口径、闸门阈值、白名单</td><td>票面要求「写操作仅管理员」，admin 是全集</td></tr>
 *   <tr><td>{@code DBA}</td><td>仅白名单（表/字段）</td><td>白名单是「库里有哪些列」的事实，归 DBA；
 *       但他不该能去改业务口径或放宽闸门 —— 所以 DBA 是 admin 的子集，不是它的别名</td></tr>
 * </table>
 *
 * <h2>两个刻意的取舍</h2>
 * <ol>
 *   <li><b>不用 session、不做登录页</b>：STATELESS + Basic，每个写请求自带凭证。
 *       前端（Ticket 13）没有登录界面，只加了一个「写入凭证」输入框，存 sessionStorage。
 *       做完整会话/登录页是 Spec §6 分期的事，本票只解决「有没有身份」。</li>
 *   <li><b>关掉 CSRF</b>：CSRF 防护的前提是「浏览器会自动带上凭证」。
 *       这里凭证是显式放在 Authorization 头里的，不会被浏览器自动附加，CSRF 攻击无从下手。
 *       反过来，开着 CSRF 会让所有 PUT 直接 403，前端每个写请求都要多一步取 token —— 净负担。</li>
 * </ol>
 *
 * <p>401/403 都返回 <b>JSON</b> 而不是 Spring 默认的空响应体：前端拿不到原因就只能显示「操作失败」，
 * 而这里最需要说清的就是「你是没登录，还是登录了但角色不够」——这两种情况的处置完全不一样。
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    public static final String ROLE_BUSINESS = "BUSINESS";
    public static final String ROLE_ADMIN = "ADMIN";
    public static final String ROLE_DBA = "DBA";

    private final String businessUser;
    private final String businessPassword;
    private final String adminUser;
    private final String adminPassword;
    private final String dbaUser;
    private final String dbaPassword;

    /**
     * 账号走配置注入、默认值写在这里，而不是散在 yml 里：
     * 单机作品不需要「配置文件里放三个账号」，但需要「能覆盖」——
     * 用 {@code DATAPILOT_ADMIN_PASSWORD} 这类环境变量即可，不改代码、不改打包。
     */
    public SecurityConfig(
            @Value("${datapilot.auth.business.username:business}") String businessUser,
            @Value("${datapilot.auth.business.password:Biz@2026}") String businessPassword,
            @Value("${datapilot.auth.admin.username:admin}") String adminUser,
            @Value("${datapilot.auth.admin.password:Admin@2026}") String adminPassword,
            @Value("${datapilot.auth.dba.username:dba}") String dbaUser,
            @Value("${datapilot.auth.dba.password:Dba@2026}") String dbaPassword) {
        this.businessUser = businessUser;
        this.businessPassword = businessPassword;
        this.adminUser = adminUser;
        this.adminPassword = adminPassword;
        this.dbaUser = dbaUser;
        this.dbaPassword = dbaPassword;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public UserDetailsService userDetailsService(PasswordEncoder encoder) {
        UserDetails business = User.withUsername(businessUser)
                .password(encoder.encode(businessPassword)).roles(ROLE_BUSINESS).build();
        UserDetails admin = User.withUsername(adminUser)
                .password(encoder.encode(adminPassword)).roles(ROLE_ADMIN).build();
        UserDetails dba = User.withUsername(dbaUser)
                .password(encoder.encode(dbaPassword)).roles(ROLE_DBA).build();
        return new InMemoryUserDetailsManager(business, admin, dba);
    }

    @Bean
    public SecurityFilterChain apiFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .formLogin(form -> form.disable())
                .logout(logout -> logout.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                // 顺序即优先级：写规则必须排在 permitAll 之前，否则被后面那条吞掉。
                // 这是 Spring Security 最容易写错的地方 —— 它取「第一条匹配」，不是「最严格的一条」。
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.PUT, "/api/metrics").hasRole(ROLE_ADMIN)
                        .requestMatchers(HttpMethod.PUT, "/api/gate-config").hasRole(ROLE_ADMIN)
                        .requestMatchers(HttpMethod.PUT, "/api/metadata/**").hasAnyRole(ROLE_ADMIN, ROLE_DBA)
                        .requestMatchers(HttpMethod.DELETE, "/api/metadata/**").hasAnyRole(ROLE_ADMIN, ROLE_DBA)
                        .anyRequest().permitAll())
                .httpBasic(basic -> basic.authenticationEntryPoint(unauthorized()))
                .exceptionHandling(e -> e
                        .authenticationEntryPoint(unauthorized())
                        .accessDeniedHandler(forbidden()));
        return http.build();
    }

    /**
     * 未带凭证 / 凭证错误 → 401。
     *
     * <p><b>刻意不写 {@code WWW-Authenticate} 头</b>。规范上 401 应该带它，但带上之后
     * Chrome 会把响应当成「要弹原生登录框」的挑战：headless 里直接让 fetch 挂住不返回
     * （冒烟脚本实测 `Runtime.callFunctionOn timed out`），真人浏览器里则会盖一个与页面无关的
     * 系统弹窗 —— 而本项目的设计恰恰是「没有登录页，凭证由页面上一个输入框设置并显式放进
     * Authorization 头」。challenge 与这套设计冲突，所以只保留 JSON 原因。
     */
    private static AuthenticationEntryPoint unauthorized() {
        return (request, response, ex) -> writeJson(response, HttpServletResponse.SC_UNAUTHORIZED,
                "未认证：该写接口需要 HTTP Basic 凭证（管理员 admin 或 DBA dba）");
    }

    /** 有身份但角色不够 → 403（与 401 分开：前者是「补齐权限」，后者是「补凭证」） */
    private static AccessDeniedHandler forbidden() {
        return (request, response, ex) -> writeJson(response, HttpServletResponse.SC_FORBIDDEN,
                "权限不足：当前身份没有该写操作的权限（指标/闸门仅管理员，白名单需管理员或 DBA）");
    }

    private static void writeJson(HttpServletResponse response, int status, String message)
            throws java.io.IOException {
        // 固定文案，不含入参，无需转义；charset 一定要写，否则中文在部分客户端是乱码
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write("{\"error\":\"" + message + "\",\"status\":" + status + "}");
        response.getWriter().flush();
    }
}
