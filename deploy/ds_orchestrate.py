# -*- coding: utf-8 -*-
"""DolphinScheduler 编排：创建 DataPilot 项目 + 5 Shell 任务串行工作流 + 上线 + 运行
DS 3.2.2 API 用 @RequestParam（form 参数），统一用 urlencode 编码。
每个 Shell 任务幂等提交对应 Dinky 作业（已在 Flink RUNNING 则跳过）。
"""
import json, urllib.request, urllib.error, urllib.parse, sys

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


JOBS = [
    ("job1_cdc_to_kafka", 2),
    ("job2_kafka_to_ods", 3),
    ("job3_ods_to_dwd", 5),
    ("job4_dwd_to_dws", 6),
    ("job5_dws_to_ads", 7),
]


def shell_script(job_name, task_id):
    return f"""#!/bin/bash
JOB_NAME="{job_name}"
TASK_ID={task_id}
TOKEN=$(curl -s -X POST http://dinky:8888/api/login -H 'Content-Type: application/json' -d '{{"username":"admin","password":"Dinky@2026"}}' -D - | grep -i '^dinky-token:' | awk '{{print $2}}' | tr -d '\\r')
if curl -s http://flink-jobmanager:8081/jobs/overview | grep -oP '"name":"[^"]*"[^{{}}]*"state":"RUNNING"' | grep -q "$JOB_NAME"; then
  echo "SKIP $JOB_NAME (already RUNNING)"
else
  echo "SUBMIT $JOB_NAME"
  curl -s "http://dinky:8888/api/task/submitTask?id=$TASK_ID" -H "dinky-token: $TOKEN"
  echo
fi
"""


def build_definition():
    tasks, rels, locs = [], [], []
    codes = [1001 + i for i in range(len(JOBS))]
    for i, ((job_name, task_id), code) in enumerate(zip(JOBS, codes)):
        tasks.append({
            "code": code, "name": job_name, "version": 1,
            "description": f"提交 Dinky 作业 {job_name}", "taskType": "SHELL",
            "taskParams": {"localParams": [], "resourceList": [], "rawScript": shell_script(job_name, task_id)},
            "flag": "YES", "taskPriority": "MEDIUM", "workerGroup": "default",
            "environmentCode": -1, "failRetryTimes": 1, "failRetryInterval": 1,
            "timeoutFlag": "CLOSE", "timeoutNotifyStrategy": None, "timeout": 0,
            "delayTime": 0, "resourceIds": "",
            "taskGroupId": 0, "taskGroupPriority": 0,
            "cpuQuota": -1, "memoryMax": -1, "taskExecuteType": "BATCH",
            "isCache": "NO",
        })
        locs.append({"taskCode": code, "x": 100 + (i % 5) * 220, "y": 100 + (i // 5) * 150})
    for i, code in enumerate(codes):
        pre = 0 if i == 0 else codes[i - 1]
        rels.append({"name": "", "preTaskCode": pre, "preTaskVersion": 0 if pre == 0 else 1,
                     "postTaskCode": code, "postTaskVersion": 1,
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
        print("创建项目:", json.dumps(r, ensure_ascii=False)[:300])
        proj_code = r.get("data", {}).get("code")
        if proj_code is None:
            print("创建项目失败"); sys.exit(1)
    else:
        print("复用项目 DataPilot code=", proj_code)

    # 2. 创建 process definition（form 参数，taskRelationJson/taskDefinitionJson 是 JSON 字符串）
    tasks, rels, locs = build_definition()
    r = call("POST", f"/projects/{proj_code}/process-definition", sid, {
        "name": "CDC链路一键调度",
        "description": "MySQL CDC→Kafka→Flink→Paimon(四层湖留存)+StarRocks(ADS热查询) 五作业串行",
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

    # 4. 运行
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
