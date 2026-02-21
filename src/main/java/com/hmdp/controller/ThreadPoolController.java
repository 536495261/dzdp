package com.hmdp.controller;

import com.hmdp.dto.Result;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 线程池状态查询接口
 */
@RestController
@RequestMapping("/thread-pool")
public class ThreadPoolController {

    @Resource
    @Qualifier("cacheRebuildExecutor")
    private ThreadPoolExecutor cacheRebuildExecutor;

    @Resource
    @Qualifier("seckillOrderExecutor")
    private ThreadPoolExecutor seckillOrderExecutor;

    @GetMapping("/status")
    public Result getStatus() {
        Map<String, Object> result = new HashMap<>();
        result.put("cacheRebuild", getPoolStatus(cacheRebuildExecutor));
        result.put("seckillOrder", getPoolStatus(seckillOrderExecutor));
        return Result.ok(result);
    }

    private Map<String, Object> getPoolStatus(ThreadPoolExecutor executor) {
        Map<String, Object> status = new HashMap<>();
        status.put("corePoolSize", executor.getCorePoolSize());
        status.put("maximumPoolSize", executor.getMaximumPoolSize());
        status.put("poolSize", executor.getPoolSize());
        status.put("activeCount", executor.getActiveCount());
        status.put("queueSize", executor.getQueue().size());
        status.put("queueRemainingCapacity", executor.getQueue().remainingCapacity());
        status.put("completedTaskCount", executor.getCompletedTaskCount());
        status.put("taskCount", executor.getTaskCount());
        
        // 计算队列使用率
        int queueSize = executor.getQueue().size();
        int queueCapacity = queueSize + executor.getQueue().remainingCapacity();
        double queueUsage = queueCapacity > 0 ? (double) queueSize / queueCapacity * 100 : 0;
        status.put("queueUsagePercent", String.format("%.2f%%", queueUsage));
        
        return status;
    }
}
