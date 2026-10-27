package cn.zhixiang.platform.navigation.controller;

import cn.zhixiang.platform.identity.model.Actor;
import cn.zhixiang.platform.navigation.dto.MenuDtos;
import cn.zhixiang.platform.navigation.service.MenuService;
import cn.zhixiang.platform.shared.dto.PageResult;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/** 各端导航读取接口，服务端从身份确定平台并过滤权限与完整祖先链。 */
@RestController
@RequestMapping("/api/v1")
public class MenuDictionaryController {
    private final MenuService service;

    /**
     * 初始化模块组件，所需端口和配置由构造器注入。
     *
     * @param service 导航用例
     */
    public MenuDictionaryController(MenuService service) {
        this.service = service;
    }

    /**
     * 三端通用的当前账户导航，不要求管理员具有菜单配置管理权限。
     *
     * @param page 页码，从1开始
     * @param size 每页数量，最大100
     * @param actor 服务端认证主体
     * @return 当前平台配置状态和可见菜单页
     */
    @GetMapping("/auth/navigation")
    @PreAuthorize("isAuthenticated()")
    public MenuDtos.Navigation navigation(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "100") int size,
            @AuthenticationPrincipal Actor actor) {
        return service.navigation(new MenuDtos.Query(page, size, null, null, null), actor);
    }

    /**
     * 返回可见菜单。
     *
     * @param page 页码
     * @param size 页大小
     * @param q 名称代码搜索
     * @param status 启停过滤，DISABLED返回空
     * @param sort 白名单排序，默认sortOrder:asc
     * @param actor 当前学生
     * @return 可见菜单
     */
    @GetMapping("/student/dictionaries/menus")
    @PreAuthorize("hasAuthority('get_student_dictionaries_menus')")
    public PageResult<MenuDtos.View> student(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String sort,
            @AuthenticationPrincipal Actor actor) {
        return service.visible(new MenuDtos.Query(page, size, q, status, sort), actor);
    }

    /**
     * 返回可见菜单。
     *
     * @param page 页码
     * @param size 页大小
     * @param q 名称代码搜索
     * @param status 启停过滤，DISABLED返回空
     * @param sort 白名单排序，默认sortOrder:asc
     * @param actor 当前中介
     * @return 可见菜单
     */
    @GetMapping("/partner/dictionaries/menus")
    @PreAuthorize("hasAuthority('get_partner_dictionaries_menus')")
    public PageResult<MenuDtos.View> partner(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String sort,
            @AuthenticationPrincipal Actor actor) {
        return service.visible(new MenuDtos.Query(page, size, q, status, sort), actor);
    }
}
