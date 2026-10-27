package cn.zhixiang.platform.forum;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import cn.zhixiang.platform.identity.Actor;
import cn.zhixiang.platform.knowledge.*;
import cn.zhixiang.platform.shared.*;
/** 论坛正文与审核用例；写锁顺序为账户（幂等入口）→标签树→按ID排序的板块→帖子。 */
@Service
public class PostService {
 private final PostMapper posts;private final BoardMapper boards;private final CategoryMapper tree;private final TagMapper tags;private final WriteMapper writes;
 /** 注入领域端口。
  * @param posts 帖子
  * @param boards 板块
  * @param tree 标签配置互斥
  * @param tags 标签
  * @param writes 审计事件 */
 public PostService(PostMapper posts,BoardMapper boards,CategoryMapper tree,TagMapper tags,WriteMapper writes){this.posts=posts;this.boards=boards;this.tree=tree;this.tags=tags;this.writes=writes;}
 /** 查询当前范围，隐藏帖不向学生暴露。
  * @param q 分页与字面搜索
  * @param state 状态筛选
  * @param board 板块，可空
  * @param actor 服务端身份
  * @return 当前范围分页 */
 @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
 public PageResult<PostDtos.View> list(PageQuery q,String state,UUID board,Actor actor){checkPlatform(actor);if(state!=null&&!Set.of("PENDING","PUBLISHED","HIDDEN","LOCKED").contains(state))throw new BusinessException(400,"INVALID_FILTER","帖子状态无效");return new PageResult<>(posts.list(q,state,board,isAdmin(actor),actor.id()).stream().map(this::view).toList(),q.page(),q.size(),posts.count(q,state,board,isAdmin(actor),actor.id()));}
 /** 读取当前可见正文。
  * @param id 帖子
  * @param actor 身份
  * @return 响应
  * @throws BusinessException 不存在或不可见统一404 */
 @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
 public PostDtos.View get(UUID id,Actor actor){checkPlatform(actor);var row=posts.get(id);visible(row,actor);return view(row);}
 /** 新建学生帖子；默认不接受尚未接入归属授权的附件。
  * @param w 输入
  * @param actor 学生
  * @return 新帖子
  * @throws BusinessException 停用板块、非法标签或超出限频 */
 @Transactional(rollbackFor=Exception.class)
 public PostDtos.View create(PostDtos.Write w,Actor actor){student(actor);tree.lockTree();var b=enabled(w.boardId());validate(w);if(posts.recent(actor.id())>=5)throw new BusinessException(429,"FORUM_RATE_LIMITED","发帖过于频繁，请稍后重试");UUID id=UUID.randomUUID();posts.insert(id,w,actor.id(),b.requiresReview()?"PENDING":"PUBLISHED");replaceTags(id,w);audit(id,actor,"CREATED",null);return view(posts.get(id));}
 /** 编辑本人未锁定、未隐藏的帖子，重新进入板块审核策略且清除置顶。
  * @param id 帖子
  * @param version 期望版本
  * @param w 新正文
  * @param actor 作者
  * @return 新版本 */
 @Transactional(rollbackFor=Exception.class)
 public PostDtos.View update(UUID id,long version,PostDtos.Write w,Actor actor){student(actor);tree.lockTree();var old=posts.get(id);live(old);new TreeSet<>(List.of(old.boardId(),w.boardId())).forEach(this::enabled);var row=posts.lock(id);owned(row,actor,version);if(!row.boardId().equals(old.boardId()))conflict();writable(row);validate(w);var b=boards.get(w.boardId());posts.update(id,w,b.requiresReview()?"PENDING":"PUBLISHED");replaceTags(id,w);audit(id,actor,"UPDATED",null);return view(posts.get(id));}
 /** 删除本人帖子，锁定状态禁止删除，隐藏内容可以主动删除但不能重新发布。
  * @param id 帖子
  * @param version 期望版本
  * @param actor 作者 */
 @Transactional(rollbackFor=Exception.class)
 public void delete(UUID id,long version,Actor actor){student(actor);var old=posts.get(id);live(old);boards.lock(old.boardId());var row=posts.lock(id);owned(row,actor,version);if(!row.boardId().equals(old.boardId()))conflict();if(row.state().equals("LOCKED"))invalid();posts.delete(id);audit(id,actor,"DELETED",null);}
 /** 管理员按状态机执行审核，所有动作有理由审计。
  * @param id 帖子
  * @param version CAS版本
  * @param w 审核动作
  * @param actor 管理员
  * @return 新状态 */
 @Transactional(rollbackFor=Exception.class)
 public PostDtos.View moderate(UUID id,long version,PostDtos.Moderation w,Actor actor){if(!isAdmin(actor))forbidden();var old=posts.get(id);live(old);boards.lock(old.boardId());var r=posts.lock(id);live(r);if(r.version()!=version||!r.boardId().equals(old.boardId()))conflict();String state=r.state();boolean pinned=r.pinned();switch(w.action()){
  case "APPROVE"->{if(!Set.of("PENDING","HIDDEN").contains(state))invalid();if(boards.get(r.boardId()).status()!=BoardDtos.Status.ENABLED)invalid();state="PUBLISHED";}
  case "HIDE"->{state="HIDDEN";pinned=false;}
  case "LOCK"->{if(!state.equals("PUBLISHED"))invalid();state="LOCKED";}
  case "UNLOCK"->{if(!state.equals("LOCKED"))invalid();state="PUBLISHED";}
  case "PIN"->{if(!Set.of("PUBLISHED","LOCKED").contains(state))invalid();pinned=true;}
  case "UNPIN"->pinned=false;
  default->invalid();
 }posts.moderate(id,state,pinned);audit(id,actor,w.action(),w.reason());return view(posts.get(id));}
 private BoardMapper.Row enabled(UUID id){var b=boards.lock(id);if(b==null||b.status()!=BoardDtos.Status.ENABLED)throw new BusinessException(422,"FORUM_BOARD_UNAVAILABLE","板块不存在或已停用");return b;}
 private void validate(PostDtos.Write w){if(!w.assetIds().isEmpty())throw new BusinessException(503,"FORUM_ASSETS_UNAVAILABLE","论坛附件归属流程尚未启用，请先以正文发帖");if(new HashSet<>(w.tagIds()).size()!=w.tagIds().size())throw new BusinessException(422,"DUPLICATE_TAG","标签不能重复");for(UUID id:w.tagIds()){var tag=tags.get(id);if(tag==null||tag.status()!=TagDtos.Status.ENABLED)throw new BusinessException(422,"TAG_UNAVAILABLE","标签不存在或已停用");}}
 private void replaceTags(UUID id,PostDtos.Write w){posts.clearTags(id);w.tagIds().forEach(tag->posts.tag(id,tag));}
 private void visible(PostMapper.Row r,Actor actor){live(r);if(isAdmin(actor))return;var b=boards.get(r.boardId());if(b==null||b.status()!=BoardDtos.Status.ENABLED||!(Set.of("PUBLISHED","LOCKED").contains(r.state())||(r.state().equals("PENDING")&&r.authorId().equals(actor.id()))))missing();}
 private void owned(PostMapper.Row r,Actor a,long v){live(r);if(!r.authorId().equals(a.id()))missing();if(r.version()!=v)conflict();}
 private void writable(PostMapper.Row r){if(!Set.of("PENDING","PUBLISHED").contains(r.state()))invalid();}
 private void live(PostMapper.Row r){if(r==null||r.deletedAt()!=null)missing();}
 private void student(Actor a){if(!a.platform().equals("student"))forbidden();}
 private boolean isAdmin(Actor a){return a.platform().equals("admin");}
 private void checkPlatform(Actor a){if(!Set.of("student","admin").contains(a.platform()))forbidden();}
 private void forbidden(){throw new BusinessException(403,"FORBIDDEN","当前平台不能访问论坛");}
 private void missing(){throw new BusinessException(404,"RESOURCE_NOT_FOUND","帖子不存在");}
 private void conflict(){throw new BusinessException(412,"VERSION_CONFLICT","帖子已更新，请刷新后重试");}
 private void invalid(){throw new BusinessException(422,"POST_STATE_INVALID","当前帖子状态不允许此操作");}
 private void audit(UUID id,Actor actor,String action,String reason){writes.auditReason(UUID.randomUUID(),actor.id(),action,"forum_post",id,reason);writes.event(UUID.randomUUID(),id,"forum_post."+action,"{\"id\":\""+id+"\"}");}
 private PostDtos.View view(PostMapper.Row r){return new PostDtos.View(r.id(),r.version(),r.createdAt(),r.updatedAt(),r.boardId(),r.title(),r.body(),posts.tags(r.id()),List.of(),r.authorId(),r.state(),r.pinned());}
}
