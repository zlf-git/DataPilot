# -*- coding: utf-8 -*-
"""DolphinScheduler 编排（v3）：全流改造后，出数作业全部转为流式，DS 不再调度任何
   出数作业（job1-5 流常驻自愈 + job6 手动批重建）。本脚本降级为「停用/下线」工具：
   找到旧的「ADS批作业调度(T+1)」工作流并下线，停掉 cron 定时。

架构分工（全流改造后）：
  - job1~job4 流作业：Dinky 一次性提交后常驻 RUNNING，自愈靠 Flink restart-strategy。
  - job5（流）：DWS 流读 changelog 直写 StarRocks，常驻 RUNNING。
  - job6（批）：从 DWS 全量重建 SR，手动触发（Dinky batchModel=true），不进 DS 定时。

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


# 全流改造后：不再有需要 DS 调度的批出数作业。原 job5→job6 工作流停用下线。
# （下面的 batch_shell_script / build_definition 已无调用方，保留仅为历史参考）


def main():
    sid = login()
    print("sessionId:", sid[:8] + "...")

    # 1. 找 DataPilot 项目
    r = call("GET", "/projects", sid, query={"pageNo": 1, "pageSize": 100, "searchVal": ""})
    proj_code = None
    for p in r.get("data", {}).get("totalList", []):
        if p.get("name") == "DataPilot":
            proj_code = p.get("code")
    if proj_code is None:
        print("DataPilot 项目不存在，无需停用"); return

    # 2. 找「ADS批作业调度(T+1)」工作流
    r = call("GET", f"/projects/{proj_code}/process-definition",
             sid, query={"pageNo": 1, "pageSize": 100, "searchVal": "ADS批作业调度"})
    defs = r.get("data", {}).get("totalList", [])
    if not defs:
        print("未找到「ADS批作业调度(T+1)」工作流，无需停用"); return
    for d in defs:
        def_code = d.get("code")
        # 3. 停 cron 定时
        r = call("GET", f"/projects/{proj_code}/schedules", sid,
                 query={"processDefinitionCode": def_code, "pageNo": 1, "pageSize": 100})
        scheds = r.get("data", {}).get("totalList", []) or []
        for s in scheds:
            sched_id = s.get("id")
            call("POST", f"/projects/{proj_code}/schedules/{sched_id}/offline", sid)
            print(f"停用定时 schedule id={sched_id}")
        # 4. 下线工作流
        r = call("POST", f"/projects/{proj_code}/process-definition/{def_code}/release",
                 sid, {"releaseState": "OFFLINE"})
        print(f"下线工作流 {d.get('name')} code={def_code}:",
              json.dumps(r, ensure_ascii=False)[:200])


if __name__ == "__main__":
    main()
