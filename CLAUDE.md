# mini-review (微点评)

基于 Spring Boot + MyBatis-Plus + Redis 的本地生活点评平台（类大众点评/Yelp）。

## 构建与运行

```bash
# 构建
mvn clean package -DskipTests

# 启动
mvn spring-boot:run

# 测试
mvn test
```

- 服务端口：**8081**
- 应用名称：`mini-review`
- MySQL：默认 `jdbc:mysql://127.0.0.1:3306/heima-dianping` (`root`/`123456`)，可通过 `MYSQL_URL`、`MYSQL_USERNAME`、`MYSQL_PASSWORD` 覆盖
- Redis：默认 `localhost:6379`，database 10，可通过 `REDIS_HOST`、`REDIS_PORT`、`REDIS_PASSWORD`、`REDIS_DATABASE` 覆盖
- Kafka：默认 `localhost:9092`，可通过 `KAFKA_BOOTSTRAP_SERVERS` 覆盖
- 数据库脚本：`src/main/resources/db/hmdp.sql`

## 项目结构

```
src/main/java/com/hmdp/
├── HmDianPingApplication.java   # 启动类：@SpringBootApplication + @MapperScan + @EnableAspectJAutoProxy
├── config/                       # Spring/MyBatis/Redisson 配置、拦截器注册、全局异常处理
├── controller/                   # REST 控制器 (@RestController + @RequestMapping)
├── dto/                          # 数据传输对象：Result、LoginFormDTO、UserDTO、ScrollResult
├── entity/                       # MyBatis-Plus 实体（对应 tb_* 表）
├── mapper/                       # MyBatis-Plus Mapper（继承 BaseMapper）
├── service/                      # Service 接口（继承 IService）+ impl/ 实现
└── utils/                        # 工具类：CacheClient、RedisIdWorker、分布式锁、拦截器、UserHolder
```

## 架构

**分层架构：** Controller → Service (接口 + 实现) → Mapper (MyBatis-Plus) → Entity

- 所有 Controller 返回 `Result`（统一响应封装）
- 所有 Service 实现类继承 `ServiceImpl<Mapper, Entity>`
- 所有 Mapper 继承 `BaseMapper<Entity>`
- 依赖注入：优先使用 `@Resource`

## 技术栈

| 技术 | 版本 |
|---|---|
| Java | 1.8 |
| Spring Boot | 2.3.12.RELEASE |
| MyBatis-Plus | 3.4.3 |
| Redis (Lettuce) | Spring Boot Starter |
| Redisson | 3.13.6 |
| MySQL Connector | 5.1.47 |
| Hutool | 5.7.17 |
| Lombok | 已启用 |

## Redis 使用模式

- **Key 设计：** 冒号分隔前缀（`login:code:`、`cache:shop:`、`seckill:stock:`、`feed:`、`sign:`、`lock:order:`）
- **数据类型：** String（验证码、令牌）、Hash（用户会话）、Set/ZSet（点赞、Feed）、Geo（商铺位置）、Bitmap（签到）
- **分布式锁：** 自实现 `SimpleRedisLock`（SETNX + Lua）+ Redisson `RLock`
- **Lua 脚本：** `mapper/seckill.lua`（秒杀原子操作）、`unlock.lua`（锁原子释放）
- **缓存策略：** `CacheClient` 提供穿透防护和逻辑过期两种方案
- **Token 刷新：** `RefreshTokenInterceptor`（order=0）刷新 Redis TTL；`LoginInterceptor`（order=1）校验登录状态

## 编码规范

- **接口命名：** 前缀 `I`（如 `IUserService`）
- **常量：** `UPPER_SNAKE_CASE`，放在专用常量类（`RedisConstants`、`SystemConstants`）
- **Lombok：** `@Data`、`@Slf4j`、`@Accessors(chain = true)`、`@EqualsAndHashCode(callSuper = false)`
- **Hutool：** 广泛使用 `BeanUtil.copyProperties`、`JSONUtil`、`StrUtil`、`RandomUtil`、`BooleanUtil`
- **事务：** 在 Service 方法上使用 `@Transactional`
- **ThreadLocal：** `UserHolder` 存储当前请求的用户 `UserDTO`
- **全局异常处理：** `WebExceptionAdvice` 捕获 `RuntimeException` → `Result.fail("服务器异常")`

## 核心业务实现要点

- **登录流程：** 短信验证码 → Redis 缓存 → 生成 Token → 存入 Redis Hash（带 TTL）
- **秒杀：** Lua 脚本原子扣减 Redis 库存 + 防止重复下单，然后发送 Kafka 消息异步创建订单；序列化失败或明确发送失败会补偿 Redis 库存和下单标记
- **Feed 流：** 推模式—博客发布时推送到粉丝 ZSet，滚动分页查询
- **博客点赞：** 每篇博客一个 ZSet（userId → 时间戳），按点赞时间排序
- **关注：** `tb_follow` 表 + Redis Set 实现共同关注查询
- **附近商铺：** Redis GEO 数据结构实现地理位置搜索
- **签到：** Redis Bitmap，每个用户每年一个 key
- **ID 生成：** 类雪花算法（时间戳 + Redis 按日自增）
- **登录拦截器链：** `RefreshTokenInterceptor`（始终执行）→ `LoginInterceptor`（拦截未登录请求）
- **AOP：** `@EnableAspectJAutoProxy(exposeProxy = true)` 暴露代理对象

## 测试

- 使用 JUnit 5 + Spring Boot Test；测试依赖来自 `spring-boot-starter-test`（包含 Mockito）
- 回归/单元测试：
  - `src/test/java/com/hmdp/service/impl/OptimizationRegressionTest.java`
  - `src/test/java/com/hmdp/controller/UploadControllerTest.java`
- 上下文启动测试：`src/test/java/com/hmdp/HmDianPingContextTests.java`
  - 使用 Mock RedissonClient 和 KafkaTemplate，并排除 Kafka 自动配置，避免普通测试依赖外部 Redis/Kafka
- 手动集成测试：`src/test/java/com/hmdp/HmDianPingApplicationTests.java`
  - 该类依赖本地 MySQL 和 Redis，默认 `@Disabled`
  - 主要用于店铺逻辑过期缓存预热和 Redis GEO 数据加载
- 普通验证命令：`mvn test`，应跳过 1 个手动集成测试

## 当前实现注意事项

- Kafka 发送使用异步 callback；同步抛错或异步失败会补偿 Redis 预扣状态。更强可靠性仍建议引入本地消息表或事务消息。
- 上传接口限制图片 MIME/后缀和 5MB 大小，并做规范化路径校验；删除接口为 `DELETE /upload/blog`。
- 当前 Spring Kafka 版本为 2.5.x，错误处理仍使用 `SeekToCurrentErrorHandler`。
