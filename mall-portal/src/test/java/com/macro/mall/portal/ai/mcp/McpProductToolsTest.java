package com.macro.mall.portal.ai.mcp;

import com.macro.mall.mapper.PmsProductMapper;
import com.macro.mall.mapper.PmsSkuStockMapper;
import com.macro.mall.model.PmsProduct;
import com.macro.mall.model.PmsSkuStock;
import com.macro.mall.model.PmsSkuStockExample;
import com.macro.mall.portal.ai.tool.ToolResult;
import com.macro.mall.portal.service.PmsPortalProductService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * MCP 工具层测试。
 *
 * ============================ 测什么、为什么测 ============================
 * MCP 这一步的风险不在「功能能不能跑」，而在**边界有没有守住**：
 *
 *   1. 只暴露商品工具，绝不含订单工具 —— 否则等于把水平越权从新口子放回来
 *   2. 工具签名里不含任何身份参数（memberId 之类）—— 调用方无法指定「我是谁」
 *   3. MCP 工具的返回口径与内部 Agent 一致（可用库存，不是总库存）
 *
 * 这三条如果只靠人肉 review，很容易在某次「顺手加个工具」时被破坏。
 * 所以用测试钉死：工具数量、工具名白名单、参数里禁止出现身份字段。
 *
 * Created 2026/10/27.
 */
class McpProductToolsTest {

    private PmsPortalProductService productService;
    private PmsProductMapper productMapper;
    private PmsSkuStockMapper skuStockMapper;
    private McpProductTools tools;

    @BeforeEach
    void setUp() {
        productService = mock(PmsPortalProductService.class);
        productMapper = mock(PmsProductMapper.class);
        skuStockMapper = mock(PmsSkuStockMapper.class);
        tools = new McpProductTools(productService, productMapper, skuStockMapper);
    }

    // ==================================================================
    // 一、安全边界（最重要）
    // ==================================================================

    @Test
    @DisplayName("MCP 只能暴露 2 个商品工具，且不含任何订单类工具")
    void onlyProductToolsAreExposed() {
        // 反射拿到 McpProductTools 上所有 @Tool 标注的方法名
        List<String> toolNames = new ArrayList<>();
        for (java.lang.reflect.Method m : McpProductTools.class.getDeclaredMethods()) {
            org.springframework.ai.tool.annotation.Tool t =
                    m.getAnnotation(org.springframework.ai.tool.annotation.Tool.class);
            if (t != null) {
                toolNames.add(t.name());
            }
        }

        Collections.sort(toolNames);
        assertEquals(List.of("getProductStock", "searchProducts"), toolNames,
                "MCP 暴露的工具清单被改变了 —— 新增工具前请先确认它不需要用户身份");

        // 关键断言：绝不能出现订单类工具
        for (String n : toolNames) {
            assertFalse(n.toLowerCase().contains("order"),
                    "MCP 不允许暴露订单类工具（没有 SecurityContext，会退化成水平越权）：" + n);
            assertFalse(n.toLowerCase().contains("member"),
                    "MCP 不允许暴露会员类工具：" + n);
        }
    }

    @Test
    @DisplayName("MCP 工具的任何参数里都不能出现身份字段")
    void noIdentityParameters() {
        // 这是本项目的核心安全约定：模型的输入等同于用户输入，不可信。
        // 如果 MCP 工具接收 memberId，等于让外部调用方自己声明"我是谁"。
        for (java.lang.reflect.Method m : McpProductTools.class.getDeclaredMethods()) {
            if (m.getAnnotation(org.springframework.ai.tool.annotation.Tool.class) == null) {
                continue;
            }
            for (java.lang.reflect.Parameter p : m.getParameters()) {
                String pn = p.getName().toLowerCase();
                assertFalse(pn.contains("member") || pn.contains("user") || pn.contains("token")
                                || pn.contains("session") || pn.contains("identity"),
                        "工具 " + m.getName() + " 的参数 " + p.getName()
                                + " 看起来像身份字段 —— MCP 工具绝不能让调用方指定身份");
            }
        }
    }

    // ==================================================================
    // 二、功能与口径
    // ==================================================================

    @Test
    @DisplayName("searchProducts 正常返回商品列表")
    void searchProducts_returnsResults() {
        PmsProduct p = new PmsProduct();
        p.setId(27L);
        p.setName("小米8");
        p.setSubTitle("骁龙845");
        p.setPrice(new BigDecimal("2699.00"));
        when(productService.search(anyString(), any(), any(), anyInt(), anyInt(), anyInt()))
                .thenReturn(Collections.singletonList(p));
        when(skuStockMapper.selectByExample(any(PmsSkuStockExample.class)))
                .thenReturn(Collections.emptyList());

        ToolResult<List<McpProductTools.ProductView>> r = tools.searchProducts("手机");

        assertTrue(r.isSuccess());
        assertEquals(1, r.getData().size());
        assertEquals("小米8", r.getData().get(0).getName());
    }

    @Test
    @DisplayName("searchProducts 关键词为空时给出可操作提示，且不查库")
    void searchProducts_blankKeyword() {
        ToolResult<?> r = tools.searchProducts("   ");
        assertFalse(r.isSuccess());
        assertTrue(r.getMessage().contains("关键词"));
        verify(productService, never()).search(anyString(), any(), any(), anyInt(), anyInt(), anyInt());
    }

    @Test
    @DisplayName("getProductStock 报可用库存（stock - lock_stock），与内部 Agent 口径一致")
    void getProductStock_reportsAvailable() {
        PmsProduct p = new PmsProduct();
        p.setId(26L);
        p.setName("华为 P20");
        p.setDeleteStatus(0);
        when(productMapper.selectByPrimaryKey(26L)).thenReturn(p);

        PmsSkuStock sku = new PmsSkuStock();
        sku.setId(110L);
        sku.setStock(484);
        sku.setLockStock(480);
        sku.setPrice(new BigDecimal("3788.00"));
        when(skuStockMapper.selectByExample(any(PmsSkuStockExample.class)))
                .thenReturn(Collections.singletonList(sku));

        ToolResult<List<McpProductTools.SkuStockView>> r = tools.getProductStock(26L);

        assertTrue(r.isSuccess());
        assertEquals(4, r.getData().get(0).getAvailableStock(),
                "MCP 也必须报可用库存 484-480=4，不能报总库存 484");
        assertTrue(r.getData().get(0).getInStock());
    }

    @Test
    @DisplayName("getProductStock 可用量为负时钳到 0（历史脏数据）")
    void getProductStock_negativeClamped() {
        PmsProduct p = new PmsProduct();
        p.setId(27L);
        p.setName("小米8");
        p.setDeleteStatus(0);
        when(productMapper.selectByPrimaryKey(27L)).thenReturn(p);

        PmsSkuStock sku = new PmsSkuStock();
        sku.setStock(84);
        sku.setLockStock(108);
        when(skuStockMapper.selectByExample(any(PmsSkuStockExample.class)))
                .thenReturn(Collections.singletonList(sku));

        ToolResult<List<McpProductTools.SkuStockView>> r = tools.getProductStock(27L);
        assertEquals(0, r.getData().get(0).getAvailableStock());
        assertFalse(r.getData().get(0).getInStock());
    }

    @Test
    @DisplayName("异常必须兜住，返回可读错误而非抛给 MCP 客户端")
    void tools_swallowException() {
        when(productService.search(anyString(), any(), any(), anyInt(), anyInt(), anyInt()))
                .thenThrow(new RuntimeException("数据库连接失败"));

        ToolResult<?> r = tools.searchProducts("手机");

        assertFalse(r.isSuccess());
        assertFalse(r.getMessage().contains("Exception"), "不应把异常类名喂给调用方");
        assertFalse(r.getMessage().contains("at com."), "不应包含堆栈");
    }
}
