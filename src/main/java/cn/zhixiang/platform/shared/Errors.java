package cn.zhixiang.platform.shared;

import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.bind.MissingRequestHeaderException;

/** 将可预期错误转换为 RFC ProblemDetail；内部数据库异常不直接输出。 */
@RestControllerAdvice
public class Errors {
    /** @param e 业务异常 @return 带业务码的错误体 */
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ProblemDetail> business(BusinessException e) { return response(e.status(), e.code(), e.getMessage()); }
    /** @param e 参数解析或校验错误 @return 400，不返回请求内可能含有的密码 */
    @ExceptionHandler({MethodArgumentNotValidException.class,HttpMessageNotReadableException.class,
            MethodArgumentTypeMismatchException.class,MissingRequestHeaderException.class})
    public ResponseEntity<ProblemDetail> validation(Exception e) { return response(400,"VALIDATION_ERROR","请求字段、请求头或字段格式不符合约定"); }
    /** @param e 数据库约束错误 @return 唯一性或引用冲突，不泄露表名 */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ProblemDetail> constraint(DataIntegrityViolationException e) {
        Throwable root = e.getMostSpecificCause();
        String state = root instanceof java.sql.SQLException sql ? sql.getSQLState() : "";
        return response(409,"23503".equals(state)?"RESOURCE_IN_USE":"DUPLICATE_CODE","数据存在重复或引用，请刷新后检查");
    }
    /** @param status HTTP 状态 @param code 业务码 @param detail 安全提示 @return 标准错误响应 */
    public static ResponseEntity<ProblemDetail> response(int status,String code,String detail) {
        ProblemDetail p = ProblemDetail.forStatusAndDetail(org.springframework.http.HttpStatusCode.valueOf(status),detail);
        p.setTitle(code); p.setProperty("code",code); p.setProperty("traceId",UUID.randomUUID().toString());
        p.setProperty("fieldErrors",java.util.List.of());
        return ResponseEntity.status(status).body(p);
    }
}
