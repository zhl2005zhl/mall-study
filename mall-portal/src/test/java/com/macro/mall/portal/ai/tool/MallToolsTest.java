package com.macro.mall.portal.ai.tool;

import com.macro.mall.common.api.CommonPage;
import com.macro.mall.mapper.PmsProductMapper;
import com.macro.mall.mapper.PmsSkuStockMapper;
import com.macro.mall.model.OmsOrder;
import com.macro.mall.model.OmsOrderItem;
import com.macro.mall.model.PmsProduct;
import com.macro.mall.model.PmsSkuStock;
import com.macro.mall.model.PmsSkuStockExample;
import com.macro.mall.model.UmsMember;
import com.macro.mall.portal.domain.OmsOrderDetail;
import com.macro.mall.portal.service.OmsPortalOrderService;
import com.macro.mall.portal.service.PmsPortalProductService;
import com.macro.mall.portal.service.UmsMemberService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 工具层单元测试：不接模型、不连数据库，纯 Mockito。
 *
 * ============================ 为什么工具层必须单独测 ============================
 * 工具层是整个 AI 能力里**最不安全、最容易写错、又最难发现**的部分：
 *
 *   - 身份隔离写错 → 水平越权，而且 Controller 层鉴权看起来完全正常
 *   - 结果不瘦身     → 上下文爆炸，要等到真跑模型才暴露（且表现为「模型答得乱七八糟」）
 *   - 错误信息不可读 → 模型无法自愈，Agent 变成死循环
 *
 * 这三类问题如果靠「接上模型试一下」来验证，成本高、可复现性差
 * （模型的输出是概率性的）。拆成纯函数级测试后，结论是确定的。
 *
 * 尤其是「塞满 30 个字段的订单进来，出去的必须只有 4 个字段」——
 * 这条用测试锁死，比靠人肉 review 可靠得多。
 *
 * Created 2026/10/20.
 */
class MallToolsTest {

    private OmsPortalOrderService orderService;
    private PmsPortalProductService productService;
    private UmsMemberService memberService;
    private PmsSkuStockMapper skuStockMapper;
    private PmsProductMapper productMapper;
    private com.macro.mall.mapper.OmsOrderMapper orderMapper;
    private MallTools tools;

    /** 当前登录会员 —— 身份 mock 的锚点。 */
    private static final Long CURRENT_MEMBER_ID = 1L;
    /** 别人的会员 id —— 用于越权测试。 */
    private static final Long OTHER_MEMBER_ID = 2L;

    @BeforeEach
    void setUp() {
        orderService = mock(OmsPortalOrderService.class);
        productService = mock(PmsPortalProductService.class);
        memberService = mock(UmsMemberService.class);
        skuStockMapper = mock(PmsSkuStockMapper.class);
        productMapper = mock(PmsProductMapper.class);
        orderMapper = mock(com.macro.mall.mapper.OmsOrderMapper.class);

        UmsMember member = new UmsMember();
        member.setId(CURRENT_MEMBER_ID);
        member.setUsername("test");
        when(memberService.getCurrentMember()).thenReturn(member);

        tools = new MallTools(orderService, productService, memberService,
                skuStockMapper, productMapper, orderMapper);
    }

    // ==================================================================
    // 一、身份隔离（最重要的安全测试）
    // ==================================================================

    @Test
    @DisplayName("queryMyOrders 的身份必须来自会话，而不是参数")
    void queryMyOrders_identityComesFromSession() {
        OmsOrderDetail mine = buildOrder(100L, CURRENT_MEMBER_ID, "202610200101000001", 0);
        CommonPage<OmsOrderDetail> page = pageOf(Collections.singletonList(mine));
        when(orderService.list(any(), anyInt(), anyInt())).thenReturn(page);

        ToolResult<List<com.macro.mall.portal.ai.domain.OrderSummary>> r = tools.queryMyOrders(null);

        assertTrue(r.isSuccess());
        assertEquals(1, r.getData().size());
        assertEquals("202610200101000001", r.getData().get(0).getOrderSn());
        // 关键：必须调用过 getCurrentMember，证明确实是从会话取的身份
        verify(memberService).getCurrentMember();
    }

    @Test
    @DisplayName("getOrderDetail 查他人订单必须被拒，且不泄露「订单存在」")
    void getOrderDetail_rejectsOtherMembersOrder() {
        // 该订单真实存在，但属于别人
        OmsOrder othersOrder = buildRawOrder(200L, OTHER_MEMBER_ID, "202610200101000099");
        when(orderMapper.selectByExample(any(com.macro.mall.model.OmsOrderExample.class)))
                .thenReturn(Collections.singletonList(othersOrder));

        ToolResult<com.macro.mall.portal.ai.domain.OrderDetailView> r =
                tools.getOrderDetail("202610200101000099");

        assertFalse(r.isSuccess(), "查询他人订单必须失败");
        // 关键：不能因为「越权」而暴露该订单号真实存在，否则可被枚举
        assertTrue(r.getMessage().contains("没有找到"),
                "越权时应按「找不到」回复，避免泄露订单是否存在，实际：" + r.getMessage());
        // 关键：绝不能把详情查出来
        verify(orderService, never()).detail(anyLong());
        assertNull(r.getData());
    }

    @Test
    @DisplayName("getOrderDetail 查自己的订单正常返回")
    void getOrderDetail_acceptsOwnOrder() {
        OmsOrder mine = buildRawOrder(100L, CURRENT_MEMBER_ID, "202610200101000001");
        when(orderMapper.selectByExample(any(com.macro.mall.model.OmsOrderExample.class)))
                .thenReturn(Collections.singletonList(mine));
        OmsOrderDetail detail = new OmsOrderDetail();
        detail.setOrderItemList(Collections.singletonList(buildItem("华为 HUAWEI P20", 3)));
        when(orderService.detail(100L)).thenReturn(detail);

        ToolResult<com.macro.mall.portal.ai.domain.OrderDetailView> r =
                tools.getOrderDetail("202610200101000001");

        assertTrue(r.isSuccess());
        assertTrue(r.getData().getProductNames().contains("华为 HUAWEI P20"));
        assertTrue(r.getData().getProductNames().contains("3"));
    }

    @Test
    @DisplayName("查已发货/已完成的订单也要能查到（不能复用只查待付款的 getOrderByOrderSn）")
    void getOrderDetail_findsNonPendingOrder() {
        // 一笔已发货(status=2)的订单 —— 这是真机验证时暴露的问题：
        // 之前复用 orderService.getOrderByOrderSn，它内部带 andStatusEqualTo(0)，
        // 只查待付款单，导致「有订单却查不到详情」。
        OmsOrder shipped = buildRawOrder(300L, CURRENT_MEMBER_ID, "202610200101000077");
        shipped.setStatus(2);
        when(orderMapper.selectByExample(any(com.macro.mall.model.OmsOrderExample.class)))
                .thenReturn(Collections.singletonList(shipped));
        OmsOrderDetail d = new OmsOrderDetail();
        d.setOrderItemList(Collections.singletonList(buildItem("小米8", 1)));
        when(orderService.detail(300L)).thenReturn(d);

        ToolResult<com.macro.mall.portal.ai.domain.OrderDetailView> r =
                tools.getOrderDetail("202610200101000077");

        assertTrue(r.isSuccess(), "已发货订单必须能查到，实际：" + r.getMessage());
        assertEquals("已发货", r.getData().getStatusText());
        // 关键：不能再依赖那个带 status=0 的方法
        verify(orderService, never()).getOrderByOrderSn(anyString());
    }

    // ==================================================================
    // 二、参数校验与可读错误（自愈的前提）
    // ==================================================================

    @Test
    @DisplayName("订单号为空时，错误信息必须包含可执行的下一步")
    void getOrderDetail_blankOrderSn_givesActionableError() {
        ToolResult<?> r = tools.getOrderDetail("  ");
        assertFalse(r.isSuccess());
        // 关键：错误信息要给退路，模型才知道怎么改
        assertTrue(r.getMessage().contains("queryMyOrders"),
                "空订单号的提示应引导模型改用 queryMyOrders，实际：" + r.getMessage());
    }

    @Test
    @DisplayName("订单号传成手机号时，提示要说明正确格式")
    void getOrderDetail_phoneNumberAsOrderSn_givesFormatHint() {
        ToolResult<?> r = tools.getOrderDetail("13800138000");
        assertFalse(r.isSuccess());
        assertTrue(r.getMessage().contains("18"), "应说明应为 18 位，实际：" + r.getMessage());
        assertTrue(r.getMessage().contains("13800138000"), "应回显错误输入，便于模型定位");
        assertTrue(r.getMessage().contains("queryMyOrders"), "应给出退路");
        // 关键：格式不对就不该查库，避免无谓的数据库访问
        verify(orderMapper, never()).selectByExample(any(com.macro.mall.model.OmsOrderExample.class));
    }

    @Test
    @DisplayName("订单号不存在时，提示要引导模型去列订单，而不是让它重复猜")
    void getOrderDetail_notFound_guidesToQueryMyOrders() {
        when(orderMapper.selectByExample(any(com.macro.mall.model.OmsOrderExample.class)))
                .thenReturn(Collections.emptyList());
        ToolResult<?> r = tools.getOrderDetail("202610200101999999");
        assertFalse(r.isSuccess());
        assertTrue(r.getMessage().contains("queryMyOrders"));
    }

    @Test
    @DisplayName("不传 status 时必须传 -1 给 Service（避免 Service 侧的拆箱 NPE）")
    void queryMyOrders_passesMinusOneWhenStatusNull() {
        when(orderService.list(any(), anyInt(), anyInt()))
                .thenReturn(pageOf(Collections.emptyList()));

        tools.queryMyOrders(null);

        // OmsPortalOrderServiceImpl.list 的历史实现是 if(status==-1)，
        // 传 null 会触发自动拆箱 NPE（Cannot invoke "Integer.intValue()" because "status" is null）。
        // 该缺陷已修，但工具层仍显式传 -1 做双重保险 —— 这行断言把它钉死。
        verify(orderService).list(org.mockito.ArgumentMatchers.eq(-1), anyInt(), anyInt());
    }

    // ==================================================================
    // 三、结果瘦身（上下文爆炸防线）
    // ==================================================================

    @Test
    @DisplayName("订单瘦身：30+ 字段的订单进来，只回填 4 个字段")
    void queryMyOrders_resultIsSlimmed() {
        OmsOrderDetail o = buildOrder(100L, CURRENT_MEMBER_ID, "202610200101000001", 0);
        // 塞满一堆不该回填的敏感/冗余字段
        o.setReceiverName("张三");
        o.setReceiverPhone("13800138000");
        o.setReceiverDetailAddress("某某路 1 号");
        o.setDeliverySn("SF123456789");
        o.setDeliveryCompany("顺丰");
        o.setNote("这是我塞的备注");
        o.setMemberUsername("test");

        when(orderService.list(any(), anyInt(), anyInt()))
                .thenReturn(pageOf(Collections.singletonList(o)));

        ToolResult<List<com.macro.mall.portal.ai.domain.OrderSummary>> r = tools.queryMyOrders(null);
        assertTrue(r.isSuccess());
        var summary = r.getData().get(0);

        // OrderSummary 只有 4 个 getter，这里逐项确认它们有值
        assertNotNull(summary.getOrderSn());
        assertNotNull(summary.getCreateTime());
        assertNotNull(summary.getStatusText());
        assertNotNull(summary.getTotalAmount());

        // 关键：序列化后的字段数必须是 4 —— 用反射守住这条线，
        // 防止将来有人往 OrderSummary 里加字段把体积又撑回去
        assertEquals(4, com.macro.mall.portal.ai.domain.OrderSummary.class.getDeclaredFields().length,
                "OrderSummary 必须保持 4 个字段，新增字段前先想清楚是否真的必要");
    }

    @Test
    @DisplayName("状态码必须转成人话，而不是把 0/1/2 直接喂给模型")
    void queryMyOrders_statusIsHumanReadable() {
        OmsOrderDetail pending = buildOrder(100L, CURRENT_MEMBER_ID, "202610200101000001", 0);
        OmsOrderDetail shipped = buildOrder(101L, CURRENT_MEMBER_ID, "202610200101000002", 2);
        when(orderService.list(any(), anyInt(), anyInt()))
                .thenReturn(pageOf(Arrays.asList(pending, shipped)));

        ToolResult<List<com.macro.mall.portal.ai.domain.OrderSummary>> r = tools.queryMyOrders(null);
        assertEquals("待付款", r.getData().get(0).getStatusText());
        assertEquals("已发货", r.getData().get(1).getStatusText());
    }

    // ==================================================================
    // 四、库存语义：必须报「可用」而不是「总数」
    // ==================================================================

    @Test
    @DisplayName("getProductStock 报可用库存（stock - lock_stock），不是总库存")
    void getProductStock_reportsAvailableNotTotal() {
        PmsProduct p = new PmsProduct();
        p.setId(26L);
        p.setName("华为 HUAWEI P20");
        p.setDeleteStatus(0);
        when(productMapper.selectByPrimaryKey(26L)).thenReturn(p);

        // 总 484，已锁定 480 → 可用 4
        PmsSkuStock sku = new PmsSkuStock();
        sku.setId(110L);
        sku.setProductId(26L);
        sku.setStock(484);
        sku.setLockStock(480);
        sku.setPrice(new BigDecimal("3788.00"));
        sku.setSpData("[{\"key\":\"颜色\",\"value\":\"金色\"}]");
        when(skuStockMapper.selectByExample(any(PmsSkuStockExample.class)))
                .thenReturn(Collections.singletonList(sku));

        ToolResult<List<MallTools.SkuStockView>> r = tools.getProductStock(26L);

        assertTrue(r.isSuccess());
        assertEquals(4, r.getData().get(0).getAvailableStock(),
                "必须报可用库存 484-480=4，而不是总库存 484");
        assertTrue(r.getData().get(0).getInStock());
        // 规格要转成人话
        assertTrue(r.getData().get(0).getSpecification().contains("颜色:金色"));
    }

    @Test
    @DisplayName("可用库存为负（历史脏数据）时按 0 处理，不能报负数")
    void getProductStock_negativeAvailableClampedToZero() {
        PmsProduct p = new PmsProduct();
        p.setId(27L);
        p.setName("小米8");
        p.setDeleteStatus(0);
        when(productMapper.selectByPrimaryKey(27L)).thenReturn(p);

        PmsSkuStock sku = new PmsSkuStock();
        sku.setId(98L);
        sku.setStock(84);
        sku.setLockStock(108);   // 锁定超过库存 —— 就是本项目里真实出现过的脏数据
        when(skuStockMapper.selectByExample(any(PmsSkuStockExample.class)))
                .thenReturn(Collections.singletonList(sku));

        ToolResult<List<MallTools.SkuStockView>> r = tools.getProductStock(27L);

        assertEquals(0, r.getData().get(0).getAvailableStock());
        assertFalse(r.getData().get(0).getInStock(), "负可用量必须判为无货");
    }

    // ==================================================================
    // 五、异常兜底：工具绝不把异常抛给模型
    // ==================================================================

    @Test
    @DisplayName("Service 抛异常时，工具必须兜住并返回可读错误，而不是把异常抛给模型")
    void allTools_swallowException() {
        when(orderService.list(any(), anyInt(), anyInt()))
                .thenThrow(new RuntimeException("数据库连接池耗尽"));

        ToolResult<List<com.macro.mall.portal.ai.domain.OrderSummary>> r = tools.queryMyOrders(null);

        assertFalse(r.isSuccess());
        assertNotNull(r.getMessage());
        // 关键：不能把堆栈或异常类名喂给模型，那对它没有任何帮助
        assertFalse(r.getMessage().contains("Exception"), "错误信息不应包含异常类名");
        assertFalse(r.getMessage().contains("at com."), "错误信息不应包含堆栈");
        assertTrue(r.getMessage().contains("不要编造") || r.getMessage().contains("再试"),
                "应明确告诉模型不要编造数据，实际：" + r.getMessage());
    }

    @Test
    @DisplayName("未登录时工具返回可读错误，而不是 NPE")
    void tools_whenNotLoggedIn() {
        when(memberService.getCurrentMember()).thenReturn(null);

        ToolResult<?> r1 = tools.queryMyOrders(null);
        assertFalse(r1.isSuccess());
        assertTrue(r1.getMessage().contains("登录"));

        ToolResult<?> r2 = tools.getOrderDetail("202610200101000001");
        assertFalse(r2.isSuccess());
        assertTrue(r2.getMessage().contains("登录"));
    }

    // ==================================================================
    // 六、商品搜索
    // ==================================================================

    @Test
    @DisplayName("searchProducts 关键词为空时给出可操作提示")
    void searchProducts_blankKeyword() {
        ToolResult<?> r = tools.searchProducts("  ");
        assertFalse(r.isSuccess());
        assertTrue(r.getMessage().contains("关键词"));
        // 不应无谓地打数据库
        verify(productService, never()).search(anyString(), any(), any(), anyInt(), anyInt(), anyInt());
    }

    @Test
    @DisplayName("searchProducts 搜不到时返回成功 + 换词建议，而不是报错")
    void searchProducts_noResult_suggestsNewKeyword() {
        when(productService.search(anyString(), any(), any(), anyInt(), anyInt(), anyInt()))
                .thenReturn(new ArrayList<>());

        ToolResult<List<com.macro.mall.portal.ai.domain.ProductSummary>> r =
                tools.searchProducts("不存在的商品XYZ");

        // 关键：搜不到是正常结果，不是错误 —— 报错会让模型以为工具坏了而反复重试
        assertTrue(r.isSuccess());
        assertTrue(r.getData().isEmpty());
        assertTrue(r.getMessage().contains("关键词"), "应建议换关键词");
    }

    // ==================================================================
    // 辅助
    // ==================================================================

    private CommonPage<OmsOrderDetail> pageOf(List<OmsOrderDetail> list) {
        CommonPage<OmsOrderDetail> page = new CommonPage<>();
        page.setList(list);
        page.setTotal((long) list.size());
        page.setPageNum(1);
        page.setPageSize(10);
        page.setTotalPage(1);
        return page;
    }

    private OmsOrderDetail buildOrder(Long id, Long memberId, String orderSn, Integer status) {
        OmsOrderDetail d = new OmsOrderDetail();
        d.setId(id);
        d.setMemberId(memberId);
        d.setOrderSn(orderSn);
        d.setStatus(status);
        d.setCreateTime(new Date());
        d.setTotalAmount(new BigDecimal("11364.00"));
        d.setPayAmount(new BigDecimal("11364.00"));
        return d;
    }

    private OmsOrder buildRawOrder(Long id, Long memberId, String orderSn) {
        OmsOrder o = new OmsOrder();
        o.setId(id);
        o.setMemberId(memberId);
        o.setOrderSn(orderSn);
        o.setStatus(2);
        o.setCreateTime(new Date());
        o.setPayAmount(new BigDecimal("3788.00"));
        o.setReceiverProvince("广东省");
        o.setReceiverCity("深圳市");
        o.setReceiverRegion("南山区");
        o.setReceiverDetailAddress("科技园");
        o.setDeliveryCompany("顺丰速运");
        o.setDeliverySn("SF123456789");
        o.setDeliveryTime(new Date());
        return o;
    }

    private OmsOrderItem buildItem(String name, int qty) {
        OmsOrderItem item = new OmsOrderItem();
        item.setProductName(name);
        item.setProductQuantity(qty);
        item.setProductSkuId(110L);
        item.setProductPrice(new BigDecimal("3788.00"));
        return item;
    }
}
