"""Consolidate recovered report under the original study folder; no searches/API calls."""
import hashlib
import json
import shutil
from pathlib import Path

OUT=Path(__file__).resolve().parent
COPY=OUT.parent/'java-online-taxi-jev-report-20261101'
CASE=OUT/'experiment/JavaOnlineTaxi'
read=lambda p:json.loads(p.read_text(encoding='utf-8'))
sha=lambda p:hashlib.sha256(p.read_bytes()).hexdigest()
recovery=read(OUT/'recovery-status.json')
assert recovery['status']=='success'
assert sha(CASE/'calibration.json')==recovery['sourceCalibrationSha256']
selection=read(CASE/'selection-jev.json')
numeric=read(CASE/'selection-numeric.json')
failed=read(CASE/'selection-jev-network-failed.json')
run=read(CASE/'run-jev-101.json')
assert selection['status']=='success' and selection['choice']=='C3'
assert run['run']['config']['id']==selection['choice']
text=(COPY/'analysis.md').read_text(encoding='utf-8').replace(str(COPY.resolve()),str(OUT.resolve()))
count=len(selection['attempts'])+len(failed['attempts'])
millis=selection['totalResponseMillis']+failed['totalResponseMillis']
extra=(f'\n本任务另有 {len(failed["attempts"])} 次域名解析失败尝试，记录在 [selection-jev-network-failed.json](experiment/JavaOnlineTaxi/selection-jev-network-failed.json)。'
       f'计入恢复前的尝试后，共 **{count} 次调用尝试**，其中只有 **1 次收到 HTTP 200**，其余未收到 HTTP 响应；客户端累计调用耗时 **{millis:.9f} ms**。'
       '成功响应未含 usage，总 tokens 仍为未取得，不能当作 0。网络恢复没有重跑校准，校准 JSON 的 SHA-256 前后相同。\n')
anchor='若响应不含 usage，tokens 记为未取得，不能视为 0。置信度不是测试改善概率。'
text=text.replace(anchor,anchor+'\n'+extra)
text=text.replace('python3 scripts/run-case.py run --input cocome.remodel --case CoCoME --output output/my-cocome-run',
                  'python3 scripts/run-case.py run --input java-online-taxi --case JavaOnlineTaxi --output output/my-java-taxi-run')
text+='''
## 8. 本次 Case 的结论与复现说明

### C3 相对 C5 的得失

C5 的校准 HV 为 0.7964489732617105，C3 为 0.8417417377763153。C3 相对 C5：CHD 提高 0.1124、CMQ 提高 0.2033、HV 提高约 0.045293；代价是 IFN 增加 0.7143，Instability 降低 0.0112、UCM 降低 0.0545。这可以解释其指标折中，但不是 JEV 返回的文字理由。

真实 JEV 与最大平均 HV 数值基线均选择 C3。因此，**本例不能证明 JEV 比数值选参更有效**。本次仅有一个校准种子和一个独立正式种子，也没有完成 C0 与 C3 的独立多种子配对比较。

### 代表方案的业务审查事项

- Microservice 0 包含 **19/29 个用例、8/15 个实体**，分组明显不均衡。
- Microservice 3（authTest）、Microservice 4（changeWorkStatus）、Microservice 7（trsearch）没有实体。其中 authTest 和 trsearch 的服务也没有需求或提供接口。
- initDicDistrict 与 Car、ApiDriverPointRequest、DirectionResponse 被分在 Microservice 5；这是算法在当前矩阵上的结果，不能直接认定为合理业务边界。
- 8 个服务之间有 **13 条接口配对、9 条有向服务依赖**。提供接口 11 个、需求接口 13 个，IFN=(11+13)/8=3.0；接口配对数与提供接口数不是同一个计数。
- 1.0 的合法率只表示染色体及归属规则合法，不等于需求完整、接口合理、事务正确或可部署。

详细输入问题见 [输入提取审计](input-extraction-audit.md)。后续修复解析器后，应重做校准和测试，不能沿用本次旧模型的指标作为新版本结果。

### 输出与操作

本目录已经汇总最终报告、正式实验和可视化：

- [本次 .req](visualizations/seed-101/java-online-taxi.req)
- [打开离线拆分图](visualizations/seed-101/java-online-taxi-merm.html)
- [用例图 SVG](visualizations/seed-101/java-online-taxi-usecases.svg)
- [实体与接口 SVG](visualizations/seed-101/java-online-taxi-domain.svg)
- [完整运行、输出及绘图操作说明](run-and-visualize.md)

打开 HTML 后切换两类图，选择服务可聚焦，点击实体按需展开关系；需要完整关系时勾选“全部实体关联”，需要逐个接口时勾选“接口明细”。已有 .req 可用 render-merm.py 离线重画，不调用 JEV 或搜索。

### 网络恢复及状态文件

初始受限网络下域名解析失败，pipeline-status.json 保留当时的失败状态。随后在获授权的网络环境中重试成功，见 [recovery-status.json](recovery-status.json)；完整任务的最终状态见 [completion.json](completion.json)。六组校准只执行一次，没有替换成 smoke/mock，也没有覆盖 CoCoME 等旧实验报告。

恢复时先在独立 report 目录生成报告，再将最终报告和源码审计汇总到本目录，MERM 从同一正式结果重新导出。原恢复中间报告保留；不影响本目录中的最终文件。
'''
(OUT/'analysis.md').write_text(text,encoding='utf-8')
shutil.copyfile(COPY/'source-audit.json',OUT/'source-audit.json')
completion=dict(status='success',case='JavaOnlineTaxi',input=str(OUT.parents[1]/'java-online-taxi'),
                selector='jev',choice=selection['choice'],numericChoice=numeric['choice'],seed=101,
                representativeMetrics=run['representative']['metrics'],hv=run['hv'],serviceCount=run['representative']['serviceCount'],
                calibrationSha256=sha(CASE/'calibration.json'),calibrationUnchanged=True,report=str(OUT/'analysis.md'),
                totalCallAttempts=count,httpResponseCount=1,totalClientCallMillis=millis,totalTokens=selection['totalTokens'],
                recoveryStatus=str(OUT/'recovery-status.json'),initialPipelineStatus='failed-dns-preserved')
(OUT/'completion.json').write_text(json.dumps(completion,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
print(OUT/'analysis.md')
