package cn.zhixiang.platform.knowledge;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import cn.zhixiang.platform.identity.Actor;
import cn.zhixiang.platform.shared.*;
import cn.zhixiang.platform.operations.*;
/** 历史/检索与重建入口，使用真实发布快照而非演示搜索结果。 */
@Service
public class KnowledgeQueryService {
 private final KnowledgeQueryMapper mapper;private final ContentService contents;private final ContentMapper contentMapper;private final IndexMapper indexes;private final JobService jobs;private final WriteMapper writes;
 /** 初始化。
 * @param mapper 查询端口
 * @param contents 内容授权
 * @param contentMapper 内容锁
 * @param indexes 任务入队
 * @param jobs 任务响应
 * @param writes 审计事件 */
 public KnowledgeQueryService(KnowledgeQueryMapper mapper,ContentService contents,ContentMapper contentMapper,IndexMapper indexes,JobService jobs,WriteMapper writes){this.mapper=mapper;this.contents=contents;this.contentMapper=contentMapper;this.indexes=indexes;this.jobs=jobs;this.writes=writes;}
 /** 已授权的版本历史。
 * @param id 内容
 * @param q 查询
 * @param actor 身份
 * @return 历史分页 */
 @Transactional(readOnly=true,isolation=org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
 public PageResult<KnowledgeQueryDtos.Revision> versions(UUID id,ContentDtos.Query q,Actor actor){contents.get(id,actor);boolean admin=actor.platform().equals("admin");return new PageResult<>(mapper.versions(id,q,admin,actor.id()).stream().map(r->new KnowledgeQueryDtos.Revision(r.id(),r.version(),r.createdAt(),r.updatedAt(),r.contentId(),r.versionNumber(),r.assetId(),r.markdown(),r.state())).toList(),q.page(),q.size(),mapper.versionCount(id,q,admin,actor.id()));}
 /** 关键词检索，不宣称向量或语义召回能力。
 * @param q 条件
 * @param actor 学生
 * @return 带引用命中 */
 @Transactional(readOnly=true,isolation=org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
 public PageResult<KnowledgeQueryDtos.Hit> search(ContentDtos.Query q,Actor actor){if(!actor.platform().equals("student"))throw new BusinessException(403,"FORBIDDEN","仅学生检索入口");if(q.q().isBlank()||q.status()!=null&&!q.status().equals("PUBLISHED"))throw new BusinessException(400,"INVALID_FILTER","检索词必填且只能检索已发布内容");return new PageResult<>(mapper.search(q,actor.id()).stream().map(r->new KnowledgeQueryDtos.Hit(r.contentId(),r.versionId(),r.title(),r.snippet(),r.score(),List.of(new KnowledgeQueryDtos.Citation("KNOWLEDGE",r.versionId(),r.chunkId(),0,r.quote())))).toList(),q.page(),q.size(),mapper.searchCount(q,actor.id()));}
 /** 重建当前发布版本，立即使旧分片不可查询。
 * @param id 内容
 * @param version 版本
 * @param reason 原因
 * @param actor 管理员
 * @return 真实持久任务 */
 @Transactional(rollbackFor=Exception.class)
 public JobDtos.View reindex(UUID id,long version,String reason,Actor actor){if(!actor.platform().equals("admin"))throw new BusinessException(403,"FORBIDDEN","仅管理员可重建");var c=contentMapper.lock(id);if(c==null)throw new BusinessException(404,"RESOURCE_NOT_FOUND","内容不存在");if(c.version()!=version)throw new BusinessException(412,"VERSION_CONFLICT","内容已更新");if(!c.state().equals("PUBLISHED"))throw new BusinessException(422,"CONTENT_NOT_PUBLISHED","仅重建当前发布内容");indexes.invalidate(id,actor.id());indexes.enqueue(UUID.randomUUID(),id);contentMapper.event(UUID.randomUUID(),id,"REINDEX",reason,actor.id());writes.audit(UUID.randomUUID(),actor.id(),"REINDEX","knowledge",id);return jobs.get(indexes.jobId(id));}
}
