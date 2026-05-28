# mini-review 微点评

基于 Spring Boot、MyBatis-Plus、Redis、Redisson 和 Kafka 的本地生活点评平台，业务形态类似大众点评/Yelp。项目覆盖短信验证码登录、店铺缓存、附近商铺、博客点赞与 Feed 流、关注关系、签到，以及优惠券秒杀异步下单。

## 技术栈

| 技术 | 版本/说明 |
| --- | --- |
| Java | 1.8 |
| Spring Boot | 2.3.12.RELEASE |
| MyBatis-Plus | 3.4.3 |
| Redis | Spring Data Redis + Lettuce |
| Redisson | 3.13.6 |
| Kafka | Spring Kafka |
| MySQL Connector | 5.1.47 |
| Hutool | 5.7.17 |
| Lombok | 已启用 |

## 快速开始

### 1. 准备依赖

默认本地依赖如下：

- MySQL：`127.0.0.1:3306`
- Redis：`localhost:6379`，默认 database `10`
- Kafka：`localhost:9092`

初始化数据库：

```bash
mysql -uroot -p123456 < src/main/resources/db/hmdp.sql
```

### 2. 配置环境变量

项目提供默认配置，也支持通过环境变量覆盖：

| 变量 | 默认值 |
| --- | --- |
| `MYSQL_URL` | `jdbc:mysql://127.0.0.1:3306/heima-dianping?useSSL=false&serverTimezone=UTC&characterEncoding=utf-8&useUnicode=true` |
| `MYSQL_USERNAME` | `root` |
| `MYSQL_PASSWORD` | `123456` |
| `REDIS_HOST` | `localhost` |
| `REDIS_PORT` | `6379` |
| `REDIS_PASSWORD` | 空 |
| `REDIS_DATABASE` | `10` |
| `KAFKA_BOOTSTRAP_SERVERS` | `localhost:9092` |

### 3. 构建、测试和启动

```bash
# 构建
mvn clean package -DskipTests

# 运行测试
mvn test

# 启动服务
mvn spring-boot:run
```

服务默认端口：`8081`。

## 项目结构

```text
src/main/java/com/hmdp/
├── HmDianPingApplication.java   # 启动类
├── config/                      # MVC、MyBatis、Redis、Redisson、Kafka 等配置
├── consumer/                    # Kafka 消费者
├── controller/                  # REST 接口
├── dto/                         # 统一响应和传输对象
├── entity/                      # MyBatis-Plus 实体
├── mapper/                      # MyBatis-Plus Mapper
├── service/                     # Service 接口与实现
└── utils/                       # Redis、锁、拦截器、ID 生成等工具类
```

## 核心功能

### 登录与会话

- 通过手机号获取验证码
- 验证码写入 Redis：`login:code:{phone}`
- 登录成功后生成 token
- 用户信息写入 Redis Hash：`login:token:{token}`
- `RefreshTokenInterceptor` 负责刷新 token TTL
- `LoginInterceptor` 负责拦截未登录请求
- `/user/logout` 会删除 Redis 中的 token

### 店铺缓存

- 店铺详情缓存 key：`cache:shop:{id}`
- `CacheClient` 支持缓存穿透防护和逻辑过期
- 店铺更新时删除对应缓存
- 缓存重建线程池由 Spring 管理

### 附近商铺

- 使用 Redis GEO 存储店铺坐标
- 按店铺类型维护 GEO key：`shop:geo:{typeId}`
- 支持按经纬度查询附近商铺并返回距离

### 博客点赞和 Feed 流

- 博客点赞使用 Redis ZSet：`blog:liked:{blogId}`
- ZSet score 为点赞时间戳，可查询最近点赞用户
- 点赞操作加用户/博客维度短锁，降低并发重复操作导致计数漂移的风险
- 发布博客时将博客 ID 推送到粉丝收件箱：`feed:{userId}`
- Feed 查询使用滚动分页

### 关注关系

- 关注关系落库到 `tb_follow`
- Redis Set 维护用户关注集合：`follows:{userId}`
- SQL 初始化脚本中对 `(user_id, follow_user_id)` 建立唯一索引
- 业务层处理重复关注和自关注

### 签到

- 使用 Redis Bitmap 记录签到
- key 格式：`sign:{userId}{yyyy:MM}`
- 支持统计当月连续签到天数

## 秒杀异步下单流程

秒杀下单入口：`POST /voucher-order/seckill/{id}`。

流程：

1. 获取当前登录用户 ID。
2. 执行 Redis Lua 脚本 `src/main/resources/mapper/seckill.lua`。
3. Lua 脚本原子完成：
   - 检查库存 key：`seckill:stock:{voucherId}`
   - 检查用户是否已下单：`seckill:order:{voucherId}`
   - 扣减 Redis 库存
   - 将用户 ID 写入已下单 Set
4. 生成订单 ID。
5. 将订单对象序列化后发送到 Kafka topic：`seckill.order.topic`。
6. 接口返回订单 ID。
7. Kafka 消费者异步消费消息并创建订单。
8. 消费端使用 Redisson 锁 `lock:order:{userId}` 防止同一用户并发重复处理。
9. 在事务中再次检查数据库是否已有订单，扣减 MySQL 库存并保存订单。

失败补偿：

- 订单序列化失败会补偿 Redis 预扣状态
- Kafka 同步抛异常会补偿 Redis 预扣状态
- Kafka 异步 callback 返回失败会补偿 Redis 预扣状态

补偿内容：

- `seckill:stock:{voucherId}` 加回 1
- 从 `seckill:order:{voucherId}` 移除用户 ID

更强可靠性可以继续引入本地消息表或事务消息，避免极端情况下 Redis 状态与消息投递状态不一致。

## 常用接口

| 模块 | 方法 | 路径 | 说明 |
| --- | --- | --- | --- |
| 用户 | `POST` | `/user/code` | 发送验证码 |
| 用户 | `POST` | `/user/login` | 登录 |
| 用户 | `POST` | `/user/logout` | 登出 |
| 用户 | `GET` | `/user/me` | 当前用户 |
| 用户 | `POST` | `/user/sign` | 签到 |
| 用户 | `GET` | `/user/sign/count` | 连续签到统计 |
| 店铺 | `GET` | `/shop/{id}` | 店铺详情 |
| 店铺 | `GET` | `/shop/of/type` | 按类型/坐标查询店铺 |
| 店铺类型 | `GET` | `/shop-type/list` | 店铺类型列表 |
| 博客 | `GET` | `/blog/hot` | 热门博客 |
| 博客 | `POST` | `/blog` | 发布博客 |
| 博客 | `PUT` | `/blog/like/{id}` | 点赞/取消点赞 |
| 博客 | `GET` | `/blog/of/follow` | 关注 Feed |
| 关注 | `PUT` | `/follow/{id}/{isFollow}` | 关注/取关 |
| 关注 | `GET` | `/follow/or/not/{id}` | 是否关注 |
| 关注 | `GET` | `/follow/common/{id}` | 共同关注 |
| 优惠券 | `POST` | `/voucher/seckill` | 新增秒杀券 |
| 秒杀 | `POST` | `/voucher-order/seckill/{id}` | 秒杀下单 |
| 上传 | `POST` | `/upload/blog` | 上传博客图片 |
| 上传 | `DELETE` | `/upload/blog?name=...` | 删除博客图片 |

## 测试

普通测试命令：

```bash
mvn test
```

当前测试包括：

- `HmDianPingContextTests`：Spring 上下文启动测试，使用 Mock RedissonClient 和 KafkaTemplate，并排除 Kafka 自动配置
- `OptimizationRegressionTest`：核心优化回归测试
- `UploadControllerTest`：上传接口安全测试
- `HmDianPingApplicationTests`：手动集成测试，默认 `@Disabled`，依赖本地 MySQL 和 Redis，主要用于缓存/GEO 数据预热

普通 `mvn test` 预期会跳过 1 个手动集成测试。

## 实现注意事项

- 当前 Kafka 错误处理基于 Spring Kafka 2.5.x 的 `SeekToCurrentErrorHandler`。
- Kafka 发送失败补偿适合当前项目规模；如果需要更强一致性，建议引入本地消息表或事务消息。
- 上传接口限制 MIME、后缀和 5MB 大小，并做路径规范化校验。
- 数据库脚本已为关注关系增加 `(user_id, follow_user_id)` 唯一索引。
