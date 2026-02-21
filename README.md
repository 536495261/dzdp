# 黑马点评 (HM DianPing)

一个基于Spring Boot的仿大众点评项目，实现了用户登录、商家查询、优惠券秒杀、博客发布等功能，并加入了多种性能优化方案。

## 项目简介

本项目是一个完整的点评类应用后端服务，涵盖了以下核心功能：

- **用户系统**：基于Redis实现分布式Session登录，支持Token刷新机制
- **商家模块**：商铺信息查询、分类展示、附近商铺搜索（基于GeoHash）
- **优惠券秒杀**：高并发秒杀系统，采用Redis+Lua脚本实现原子性操作
- **博客系统**：发布博客、点赞、评论、关注功能，基于推模式实现Feed流
- **AI功能**：集成DeepSeek AI提供智能客服功能

## 技术栈

| 技术 | 版本 | 用途 |
|------|------|------|
| Spring Boot | 2.3.12.RELEASE | 基础框架 |
| MySQL | 5.1.47 | 数据持久化 |
| Redis | 6.x | 缓存、分布式锁、消息队列 |
| Redisson | 3.13.6 | 分布式锁、限流 |
| MyBatis-Plus | 3.4.3 | ORM框架 |
| Caffeine | 2.9.3 | 本地缓存 |
| RabbitMQ | - | 消息队列（缓存一致性） |
| Hutool | 5.8.22 | 工具类库 |
| LangChain4j | - | AI集成 |

## 核心功能与优化

### 1. 缓存策略

- **多级缓存**：Caffeine本地缓存 + Redis分布式缓存
- **缓存一致性**：采用Cache Aside模式 + 延迟双删 + RabbitMQ消息队列保证最终一致性
- **缓存穿透**：布隆过滤器 + 空值缓存
- **缓存击穿**：互斥锁 + 逻辑过期
- **缓存雪崩**：随机TTL + 多级缓存

### 2. 秒杀系统优化

- **库存预扣减**：Redis原子性操作（Lua脚本）
- **异步下单**：消息队列削峰填谷
- **一人一单**：Redis分布式锁防止超卖
- **限流保护**：滑动窗口限流算法

### 3. 线程池监控

- 自定义线程池配置
- 实时线程池状态监控
- 动态线程池参数调整

### 4. 限流方案

- **滑动窗口限流**：基于Redis的滑动窗口算法，防止恶意请求
- **注解式限流**：通过自定义注解实现方法级限流控制

## 项目结构

```
hm-dianping/
├── src/main/java/com/hmdp/
│   ├── ai/                 # AI相关功能
│   ├── annotation/         # 自定义注解
│   ├── aspect/             # AOP切面
│   ├── config/             # 配置类
│   ├── controller/         # 控制器层
│   ├── dto/                # 数据传输对象
│   ├── entity/             # 实体类
│   ├── exception/          # 自定义异常
│   ├── mapper/             # MyBatis映射器
│   ├── monitor/            # 监控相关
│   ├── mq/                 # 消息队列
│   ├── service/            # 业务逻辑层
│   └── utils/              # 工具类
├── src/main/resources/
│   ├── db/                 # 数据库脚本
│   ├── mapper/             # MyBatis XML
│   └── *.lua               # Lua脚本
└── pom.xml
```

## 快速开始

### 环境要求

- JDK 1.8+
- MySQL 5.7+
- Redis 6.x
- RabbitMQ 3.x（可选）

### 配置说明

1. 修改 `application.yaml` 中的数据库和Redis连接信息：

```yaml
spring:
  datasource:
    url: jdbc:mysql://localhost:3306/hmdp?useSSL=false&serverTimezone=UTC
    username: root
    password: your_password
  redis:
    host: localhost
    port: 6379
    password: your_password
```

2. 执行数据库脚本：`src/main/resources/db/hmdp.sql`

3. 启动应用：

```bash
mvn spring-boot:run
```

### API文档

项目启动后访问：`http://localhost:8081`

## 性能优化亮点

1. **多级缓存架构**：本地缓存（Caffeine）+ 分布式缓存（Redis），降低网络开销
2. **异步处理**：使用线程池异步处理耗时操作，提升接口响应速度
3. **消息队列**：RabbitMQ实现缓存删除的异步处理，保证数据一致性
4. **限流保护**：滑动窗口限流防止系统过载
5. **分布式锁**：Redisson实现高并发场景下的数据安全

## 待优化项

- [ ] 引入Elasticsearch实现全文搜索
- [ ] 分库分表支持
- [ ] 容器化部署（Docker + K8s）
- [ ] 全链路监控（SkyWalking）

## 许可证

本项目仅供学习交流使用。
