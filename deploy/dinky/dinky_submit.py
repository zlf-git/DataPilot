# -*- coding: utf-8 -*-
"""Dinky 提交 FlinkSQL 作业：登录 -> chooseTenant -> 保存(带id/type=standalone) -> 提交"""
import json, sys, subprocess, urllib.request, urllib.error

BASE = "http://localhost:8888"
CLUSTER_ID = 2  # flink-standalone 集群 id


def login():
    body = json.dumps({"username": "admin", "password": "Dinky@2026"}).encode("utf-8")
    req = urllib.request.Request(BASE + "/api/login", data=body, method="POST")
    req.add_header("Content-Type", "application/json")
    r = urllib.request.urlopen(req, timeout=60)
    return r.headers.get("dinky-token") or r.headers.get("Dinky-Token")


def api(method, path, token, body=None, query=None):
    url = BASE + path
    if query:
        url += "?" + "&".join(f"{k}={v}" for k, v in query.items())
    data = json.dumps(body).encode("utf-8") if body is not None else None
    req = urllib.request.Request(url, data=data, method=method)
    req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("dinky-token", token)
    try:
        r = urllib.request.urlopen(req, timeout=180)
        return json.loads(r.read().decode("utf-8"))
    except urllib.error.HTTPError as e:
        return {"_http_error": e.code, "_body": e.read().decode("utf-8")[:1000]}


def query_task_id(name):
    """通过 docker exec mysql 按 name 查 task id"""
    sql = f"SELECT id FROM dinky.dinky_task WHERE name='{name}' ORDER BY id DESC LIMIT 1;"
    out = subprocess.check_output(
        ["docker", "exec", "mysql-ai-analysis", "mysql", "-uroot", "-proot123",
         "-N", "-e", sql]).decode().strip()
    lines = [l for l in out.splitlines() if l.strip().isdigit()]
    return int(lines[0]) if lines else None


def save_task(token, name, statement, task_id=None, batch_model=False):
    body = {
        "name": name,
        "dialect": "FlinkSQL",
        "type": "standalone",
        "statement": statement,
        "clusterId": CLUSTER_ID,
        "enabled": True,
        "step": 1,
        "batchModel": batch_model,
        "note": f"DataPilot 链路作业 {name}",
    }
    if task_id:
        body["id"] = task_id
    return api("PUT", "/api/task", token, body=body)


def main():
    sql_file = sys.argv[1]
    job_name = sys.argv[2] if len(sys.argv) > 2 else sql_file.split("/")[-1].replace(".sql", "")
    statement = open(sql_file, encoding="utf-8").read()

    token = login()
    if not token:
        print("ERROR: login token 为空"); sys.exit(1)
    print("token:", token[:8] + "...")

    api("POST", "/api/chooseTenant", token, query={"tenantId": 1})

    task_id = query_task_id(job_name)
    print("task_id:", task_id)

    # 全流改造后：job1-5 均为流作业（job5=DWS 流写 SR），只有 job6 是批模式
    # （从 DWS 全量重建 SR 的补数/回刷入口）。
    batch_model = job_name.startswith("job6")
    r = save_task(token, job_name, statement, task_id, batch_model=batch_model)
    print("save:", json.dumps(r, ensure_ascii=False)[:400])

    if task_id is None:
        task_id = query_task_id(job_name)
        print("new task_id:", task_id)

    r = api("GET", "/api/task/submitTask", token, query={"id": task_id})
    print("submit:", json.dumps(r, ensure_ascii=False)[:400])


if __name__ == "__main__":
    main()
