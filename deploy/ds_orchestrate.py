# -*- coding: utf-8 -*-
"""DolphinScheduler 编排（v2）：只调度 2 个批作业 job5→job6，流作业 job1-4 不放入 DS。

架构分工（评审修正后）：
  - job1~job4 流作业：Dinky 一次性提交后常驻 RUNNING，自愈靠 Flink restart-strategy
    （已配 fixed-delay），不在 DS 里重复调度。
  - job5（批，T+1 写湖）+ job6（批，从湖同步仓）：DS 串行 DAG 依赖 + cron 定时。
    job5 的 Shell 脚本会轮询 Flink 等 job5 批作业真正 FINISHED 才返回，保证 job6
    读到的 Paimon ADS 是 job5 写完的完整快照（避免「写湖未完成就同步仓」读空）。

DS 3.2.2 API 用 @RequestParam（form 参数），统一 urlencode 编码。
"""
import json, urllib.request, urllib.error, urllib.parse, sys, time

DS = "http://localhost:12345/dolphinscheduler"


def call(method, path, sid, params=None, query=None):
    """params: dict -> form-urlencoded body；query: dict -> URL query 参数"""
    url = DS + path
    if query:
        url += "?" + urllib.parse.urlencode(query)
    data = urllib.parse.urlencode(params).encode("utf-8") if params is not None else None
    req = urllib.request.Request(url, data=data, method=method)
    if params is not None:
        req.add_header("Content-Type", "application/x-www-form-urlencoded")
    if sid:
        req.add_header("sessionId", sid)
    try:
        r = urllib.request.urlopen(req, timeout=60)
        return json.loads(r.read().decode("utf-8"))
    except urllib.error.HTTPError as e:
        return {"_http_error": e.code, "_body": e.read().decode("utf-8")[:600]}


def login():
    return call("POST", "/login", None, {"userName": "admin", "userPassword": "dolphinscheduler123"})["data"]["sessionId"]


# 只调度 2 个批作业（task_id 为 Dinky 里的作业 id）
BATCH_JOBS = [
    ("job5_dws_to_ads", 7),
    ("job6_ads_lake_to_sr", 9),
]

# T+1 全量重算：每天 02:00 触发（Quartz 7 段 cron）
CRONTAB = "0 0 2 * * ? *"


def batch_shell_script(job_name, task_id):
    """批作业脚本：提交 Dinky 作业 → 轮询 Flink 等该作业 FINISHED（计数增加法）。

    为什么不用「grep 到 FINISHED 就返回」：Flink jobs/overview 里残留历史批作业
    的 FINISHED 实例，直接 grep 会误判。改为记录提交前的 FINISHED 计数，提交后
    等计数 +1，即代表「本次新实例已跑完」。
    """
    return f"""#!/bin/bash
JOB_NAME="{job_name}"
TASK_ID={task_id}
TOKEN=$(curl -s -X POST http://dinky:8888/api/login -H 'Content-Type: application/json' -d '{{"username":"admin","password":"Dinky@2026"}}' -D - | grep -i '^dinky-token:' | awk '{{print $2}}' | tr -d '\\r')

# 记录提交前的 FINISHED 实例数（避免历史实例干扰）。
# 注意：Flink overview 是单行 JSON，必须用 `grep -o ... | wc -l` 统计匹配次数，
# 不能用 `grep -c`（它统计的是「匹配行数」，单行时恒为 1）。
BEFORE=$(curl -s http://flink-jobmanager:8081/jobs/overview | grep -oP '"name":"'"$JOB_NAME"'"[^{{}}]*"state":"FINISHED"' | wc -l)

echo "SUBMIT $JOB_NAME (task_id=$TASK_ID, BEFORE_FINISHED=$BEFORE)"
curl -s "http://dinky:8888/api/task/submitTask?id=$TASK_ID" -H "dinky-token: $TOKEN" > /dev/null
echo

# 轮询等本次新实例 FINISHED（最多 6 分钟）
for i in $(seq 1 120); do
  AFTER=$(curl -s http://flink-jobmanager:8081/jobs/overview | grep -oP '"name":"'"$JOB_NAME"'"[^{{}}]*"state":"FINISHED"' | wc -l)
  FAILED=$(curl -s http://flink-jobmanager:8081/jobs/overview | grep -oP '"name":"'"$JOB_NAME"'"[^{{}}]*"state":"FAILED"' | wc -l)
  if [ "$AFTER" -gt "$BEFORE" ]; then echo "OK $JOB_NAME FINISHED"; exit 0; fi
  if [ "$FAILED" -ge 1 ]; then echo "FAIL $JOB_NAME"; exit 1; fi
  sleep 3
done
echo "TIMEOUT $JOB_NAME"
exit 1
"""


def build_definition():
    tasks, rels, locs = [], [], []
    codes = [2001, 2002]  # job5, job6 固定节点 code
    for (job_name, task_id), code in zip(BATCH_JOBS, codes):
        tasks.append({
            "code": code, "name": job_name, "version": 1,
            "description": f"批作业 {job_name}（T+1 全量重算）", "taskType": "SHELL",
            "taskParams": {"localParams": [], "resourceList": [], "rawScript": batch_shell_script(job_name, task_id)},
            "flag": "YES", "taskPriority": "MEDIUM", "workerGroup": "default",
            "environmentCode": -1, "failRetryTimes": 1, "failRetryInterval": 1,
            "timeoutFlag": "CLOSE", "timeoutNotifyStrategy": None, "timeout": 0,
            "delayTime": 0, "resourceIds": "",
            "taskGroupId": 0, "taskGroupPriority": 0,
            "cpuQuota": -1, "memoryMax": -1, "taskExecuteType": "BATCH",
            "isCache": "NO",
        })
        locs.append({"taskCode": code, "x": 100 + len(tasks) * 220, "y": 100})
    # 根节点 job5（pre=0 标记入口）+ job6 依赖 job5（串行）
    rels.append({"name": "", "preTaskCode": 0, "preTaskVersion": 0,
                 "postTaskCode": 2001, "postTaskVersion": 1,
                 "conditionType": "NONE", "conditionParams": {}})
    rels.append({"name": "", "preTaskCode": 2001, "preTaskVersion": 1,
                 "postTaskCode": 2002, "postTaskVersion": 1,
                 "conditionType": "NONE", "conditionParams": {}})
    return tasks, rels, locs


def main():
    sid = login()
    print("sessionId:", sid[:8] + "...")

    # 1. 项目（已有则复用）
    r = call("GET", "/projects", sid, query={"pageNo": 1, "pageSize": 100, "searchVal": ""})
    proj_code = None
    for p in r.get("data", {}).get("totalList", []):
        if p.get("name") == "DataPilot":
            proj_code = p.get("code")
    if proj_code is None:
        r = call("POST", "/projects", sid, {"projectName": "DataPilot",
                                            "description": "DataPilot 数据平台调度", "userName": "admin"})
        proj_code = r.get("data", {}).get("code")
        if proj_code is None:
            print("创建项目失败"); sys.exit(1)
        print("创建项目 code=", proj_code)
    else:
        print("复用项目 DataPilot code=", proj_code)

    # 2. 创建 process definition（2 节点：job5→job6）
    tasks, rels, locs = build_definition()
    r = call("POST", f"/projects/{proj_code}/process-definition", sid, {
        "name": "ADS批作业调度(T+1)",
        "description": "job5写湖→job6同步仓 两个批作业串行，流作业 job1-4 由 Flink 常驻自愈",
        "globalParams": "[]",
        "locations": json.dumps(locs),
        "timeout": 0,
        "tenantCode": "default",
        "taskRelationJson": json.dumps(rels),
        "taskDefinitionJson": json.dumps(tasks),
        "executionType": "PARALLEL",
    })
    print("创建工作流:", json.dumps(r, ensure_ascii=False)[:400])
    if r.get("code") != 0:
        print("创建工作流失败，终止"); sys.exit(1)
    def_code = r["data"]["code"]
    print("processDefinitionCode =", def_code)

    # 3. 上线
    r = call("POST", f"/projects/{proj_code}/process-definition/{def_code}/release",
             sid, {"releaseState": "ONLINE"})
    print("上线:", json.dumps(r, ensure_ascii=False)[:300])

    # 4. 创建 cron 定时（每天 02:00）
    schedule = {
        "startTime": "2026-10-19 00:00:00",
        "endTime": "2099-12-31 00:00:00",
        "crontab": CRONTAB,
        "timezoneId": "Asia/Shanghai",
    }
    r = call("POST", f"/projects/{proj_code}/schedules", sid, {
        "schedule": json.dumps(schedule),
        "failureStrategy": "CONTINUE",
        "processInstancePriority": "MEDIUM",
        "warningGroupId": 0,
        "warningType": "NONE",
        "workerGroup": "default",
        "tenantCode": "default",
        "environmentCode": -1,
        "processDefinitionCode": def_code,
    })
    print("创建定时:", json.dumps(r, ensure_ascii=False)[:400])

    # 5. 立即运行一次（验证 2 节点串行 + job5 等 FINISHED 逻辑）
    r = call("POST", f"/projects/{proj_code}/executors/start-process-instance", sid, {
        "processDefinitionCode": def_code, "failureStrategy": "CONTINUE",
        "warningType": "NONE", "warningGroupId": 0, "execType": "",
        "startNodeList": "", "taskDependType": "TASK_POST",
        "runMode": "RUN_MODE_SERIAL", "processInstancePriority": "MEDIUM",
        "workerGroup": "default", "tenantCode": "default", "environmentCode": -1,
        "dryRun": 0, "scheduleTime": "", "startParams": "",
        "expectedParallelismNumber": "", "testFlag": 0,
    })
    print("运行:", json.dumps(r, ensure_ascii=False)[:400])


if __name__ == "__main__":
    main()
