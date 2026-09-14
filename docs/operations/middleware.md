# 开发中间件

安装日期：2026-09-14。已安装 MySQL 8.4.11、Redis 8.2.4、RabbitMQ 4.3.5（含管理界面）。三个服务健康检查通过；MySQL 建表/写入/读取/删除、Redis 写入/读取/删除、RabbitMQ 认证发布/读取消息均通过。临时验证数据已清理。本机经 SSH 隧道的应用连接尚未验证。

服务器：20.205.103.75，SSH 端口 2222，用户 azureuser。

部署目录：`/home/azureuser/roomflow-infra`。Compose 项目：`roomflow-dev`，仅用于开发，不与 staging/production 共享数据。

服务仅绑定服务器 127.0.0.1。Windows 开发连接前保持下列终端打开：

```powershell
ssh -p 2222 -N -o ExitOnForwardFailure=yes -L 13306:127.0.0.1:3306 -L 16379:127.0.0.1:6379 -L 15672:127.0.0.1:15672 -L 15673:127.0.0.1:5672 azureuser@20.205.103.75
```

本机 MySQL：127.0.0.1:13306，库/用户 roomflow；Redis：127.0.0.1:16379；AMQP：127.0.0.1:15673，用户/vhost roomflow；RabbitMQ 管理页：http://127.0.0.1:15672。

随机生成的服务密码仅存服务器 `.env`，不要提交到 Git 或复制到公开对话。登录服务器后自行读取：

```bash
cd ~/roomflow-infra
cat .env
```

维护命令（在服务器运行）：

```bash
cd ~/roomflow-infra
sudo docker compose ps
sudo docker compose logs --tail 100
sudo docker compose restart
sudo docker compose stop
sudo docker compose up -d --wait
```

数据使用独立命名卷，Redis 开启 AOF，所有服务自动重启并限制日志大小。避免执行 `down -v`，该命令删除数据卷。镜像按实际拉取摘要锁定，升级前先备份与验证。

这份文档不是项目全部完成或生产可用的证明。安装状态和连接验证见当次执行报告。
