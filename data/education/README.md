# 官方教育基础目录

## 院校
教育部截至2026年6月17日名单，共3196所：普通高校2952所、成人高校244所；不含港澳台。以10位学校标识码去重。
- https://www.moe.gov.cn/jyb_xxgk/s5743/s5744/202606/t20260618_1441074.html
- 普通高校附件 W020260618416094865984.xls；成人高校附件 W020260618307096079727.xls。

## 计算机相关专业（57项）
- 普通本科20项：2026年本科目录0809计算机类19项及电子信息类080717T人工智能。
  https://www.moe.gov.cn/srcsite/A08/moe_1034/s3882/202604/W020260427440749576927.pdf
- 高职专科21项：510201–510221；职业本科16项：310201–310216。依据计算机类目录及2024、2025增补。
  https://www.moe.gov.cn/s78/A07/zcs_ztzl/2017_zt06/17zt06_bznr/bznr_zyjyzyjxbz/gdzyjy_zk/zk_dzyxxdl/dzxxdl_jsjl/
  https://www.moe.gov.cn/s78/A07/zcs_ztzl/2017_zt06/17zt06_bznr/zhijiao_gzdzb/202211/P020221110364626029157.pdf
  https://oaa.cqcet.edu.cn/__local/A/5B/8D/BDF05C8FE5629139D7CA15D615A_1B515F69_1C4AA.pdf
  https://www.moe.gov.cn/srcsite/A07/moe_737/s3876_qt/202601/t20260105_1425685.html
  2025增补附件 W020260106393908779349.doc。
- 采集核对日期：2026-10-30。普通本科与职业本科在现有系统均为BACHELOR，按官方专业代码区分；专科为COLLEGE。不混入中职或研究生学科代码，不宣称所有泛计算机交叉学科均已纳入。

全国专业schoolId为空，不代表某校开设此专业。历史记录保留。`scripts/import-national-education.py`仅向docs/ui-verification-state.json指定的本机隔离API通过鉴权接口导入；同代码跳过、名称冲突记入报告，不覆盖和删除。先部署V58及对应API，后运行；脚本可重复执行，中断后重跑按代码去重。
