package cn.zhixiang.platform.forum.controller;

import cn.zhixiang.platform.forum.dto.BoardDtos;
import cn.zhixiang.platform.forum.service.BoardService;
import cn.zhixiang.platform.shared.dto.PageQuery;
import cn.zhixiang.platform.shared.dto.PageResult;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/** student平台只读字典，平台鉴权由过滤器和方法权限共同执行。 */
@RestController
@RequestMapping("/api/v1/student/dictionaries")
public class StudentBoardController {
    private final BoardService service;

    /**
     * 创建入口。
     *
     * @param service 字典用例
     */
    public StudentBoardController(BoardService service) {
        this.service = service;
    }

    /**
     * 学生可选择的启用板块。
     *
     * @param page 页码
     * @param size 页大小
     * @param q 名称代码搜索
     * @param status 启停条件，停用返回空
     * @param sort 白名单排序
     * @return 启用板块分页
     */
    @GetMapping("/forum-boards")
    @PreAuthorize("hasAuthority('get_student_dictionaries_forum_boards')")
    public PageResult<BoardDtos.View> boards(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String sort) {
        return service.available(new PageQuery(page, size, q, status, sort));
    }
}
