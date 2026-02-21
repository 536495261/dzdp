package com.hmdp.ai;

import com.hmdp.entity.Shop;
import com.hmdp.entity.ShopType;
import com.hmdp.service.IShopService;
import com.hmdp.service.IShopTypeService;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.util.List;
import java.util.stream.Collectors;

/**
 * AI可调用的店铺查询工具
 */
@Slf4j
@Component
public class ShopTools {

    @Resource
    private IShopService shopService;

    @Resource
    private IShopTypeService shopTypeService;

    @Tool("根据店铺ID查询店铺详细信息")
    public String getShopById(Long shopId) {
        log.info("AI调用工具: 查询店铺ID={}", shopId);
        Shop shop = shopService.getById(shopId);
        if (shop == null) {
            return "未找到ID为" + shopId + "的店铺";
        }
        return formatShop(shop);
    }

    @Tool("根据店铺名称模糊搜索店铺")
    public String searchShopByName(String name) {
        log.info("AI调用工具: 搜索店铺名称={}", name);
        List<Shop> shops = shopService.query()
                .like("name", name)
                .last("LIMIT 5")
                .list();
        if (shops.isEmpty()) {
            return "未找到名称包含'" + name + "'的店铺";
        }
        return shops.stream()
                .map(this::formatShop)
                .collect(Collectors.joining("\n---\n"));
    }

    @Tool("根据店铺类型查询店铺列表，typeId: 1美食 2KTV 3酒店 4文化 5运动 6美发")
    public String getShopsByType(Long typeId) {
        log.info("AI调用工具: 查询类型ID={}", typeId);
        List<Shop> shops = shopService.query()
                .eq("type_id", typeId)
                .orderByDesc("score")
                .last("LIMIT 5")
                .list();
        if (shops.isEmpty()) {
            return "该类型下暂无店铺";
        }
        return shops.stream()
                .map(this::formatShop)
                .collect(Collectors.joining("\n---\n"));
    }

    @Tool("查询评分最高的店铺，返回指定数量的高分店铺列表")
    public String getTopRatedShops(@P("要查询的店铺数量，默认5个") Integer limit) {
        int queryLimit = (limit == null || limit <= 0) ? 5 : Math.min(limit, 10);
        log.info("AI调用工具: 查询评分最高的{}家店铺", queryLimit);
        List<Shop> shops = shopService.query()
                .orderByDesc("score")
                .last("LIMIT " + queryLimit)
                .list();
        if (shops.isEmpty()) {
            return "暂无店铺数据";
        }
        return "查询到以下高分店铺：\n" + shops.stream()
                .map(this::formatShop)
                .collect(Collectors.joining("\n---\n"));
    }

    @Tool("根据商圈/区域查询店铺")
    public String getShopsByArea(String area) {
        log.info("AI调用工具: 查询商圈={}", area);
        List<Shop> shops = shopService.query()
                .like("area", area)
                .orderByDesc("score")
                .last("LIMIT 5")
                .list();
        if (shops.isEmpty()) {
            return "未找到'" + area + "'商圈的店铺";
        }
        return shops.stream()
                .map(this::formatShop)
                .collect(Collectors.joining("\n---\n"));
    }

    @Tool("查询所有店铺类型")
    public String getAllShopTypes() {
        log.info("AI调用工具: 查询所有店铺类型");
        List<ShopType> types = shopTypeService.list();
        return types.stream()
                .map(t -> String.format("ID:%d - %s", t.getId(), t.getName()))
                .collect(Collectors.joining("\n"));
    }

    @Tool("根据价格范围查询店铺，minPrice和maxPrice为人均消费金额")
    public String getShopsByPriceRange(Long minPrice, Long maxPrice) {
        log.info("AI调用工具: 查询价格范围 {} - {}", minPrice, maxPrice);
        List<Shop> shops = shopService.query()
                .ge(minPrice != null, "avg_price", minPrice)
                .le(maxPrice != null, "avg_price", maxPrice)
                .orderByAsc("avg_price")
                .last("LIMIT 5")
                .list();
        if (shops.isEmpty()) {
            return "未找到该价格范围内的店铺";
        }
        return shops.stream()
                .map(this::formatShop)
                .collect(Collectors.joining("\n---\n"));
    }

    private String formatShop(Shop shop) {
        return String.format(
                "【%s】\n" +
                "ID: %d\n" +
                "地址: %s\n" +
                "商圈: %s\n" +
                "人均: %d元\n" +
                "评分: %.1f分\n" +
                "销量: %d\n" +
                "营业时间: %s",
                shop.getName(),
                shop.getId(),
                shop.getAddress(),
                shop.getArea(),
                shop.getAvgPrice(),
                shop.getScore() / 10.0,
                shop.getSold(),
                shop.getOpenHours()
        );
    }
}
