package com.macro.mall.portal.service.impl;

import com.github.pagehelper.PageHelper;
import com.macro.mall.common.api.CommonPage;
import com.macro.mall.common.exception.Asserts;
import com.macro.mall.mapper.PmsCommentMapper;
import com.macro.mall.mapper.PmsCommentReplayMapper;
import com.macro.mall.mapper.PmsProductMapper;
import com.macro.mall.model.*;
import com.macro.mall.portal.dao.PmsCommentDao;
import com.macro.mall.portal.domain.PmsCommentParam;
import com.macro.mall.portal.domain.PmsCommentSummary;
import com.macro.mall.portal.service.PmsCommentService;
import com.macro.mall.portal.service.UmsMemberService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Date;
import java.util.List;

/**
 * 商品评价服务实现（会员侧）
 *
 * Created 2026/10/08.
 */
@Service
public class PmsCommentServiceImpl implements PmsCommentService {

    private static final Logger LOGGER = LoggerFactory.getLogger(PmsCommentServiceImpl.class);

    /** 评价审核状态：1 = 显示，0 = 隐藏 */
    private static final int SHOW_STATUS_VISIBLE = 1;

    /** 好评的星级门槛：4 星及以上算好评 */
    private static final int GOOD_STAR_THRESHOLD = 4;

    @Autowired
    private PmsCommentDao commentDao;
    @Autowired
    private PmsCommentMapper commentMapper;
    @Autowired
    private PmsCommentReplayMapper commentReplayMapper;
    @Autowired
    private PmsProductMapper productMapper;
    @Autowired
    private UmsMemberService memberService;

    @Override
    public int create(PmsCommentParam param) {
        UmsMember member = memberService.getCurrentMember();

        // ---------- 1. 商品必须存在 ----------
        PmsProduct product = productMapper.selectByPrimaryKey(param.getProductId());
        if (product == null) {
            Asserts.fail("商品不存在");
        }

        // ---------- 2. 必须买过（且有已完成的订单） ----------
        //
        // 这是整条链路里最关键的一条规则。没有它，任何登录用户都能对任何商品刷评价，
        // 评价体系就完全失去参考价值了。
        //
        // 为什么要求「已完成」而不是「已支付」：
        // 只有收到货的人才有资格评价商品本身（描述是否相符、质量如何）。
        // 这是电商评价体系的通行规则。
        int purchased = commentDao.countMemberCompletedPurchase(member.getId(), param.getProductId());
        if (purchased == 0) {
            Asserts.fail("只有购买并确认收货后才能评价该商品");
        }

        // ---------- 3. 不能重复评价 ----------
        //
        // 【已知简化】按「会员 + 商品」去重，即每人每商品只能评价一次。
        // 更精确的是按「会员 + 订单」去重（同一商品买两次可以评两次），
        // 但那需要给 pms_comment 加 order_id 并建唯一索引。取舍写在这里，方便以后升级。
        int commented = commentDao.countMemberComment(member.getId(), param.getProductId());
        if (commented > 0) {
            Asserts.fail("您已经评价过该商品了");
        }

        // ---------- 4. 落库 ----------
        //
        // 注意：下面这些字段**全部由服务端填**，没有一个来自前端入参 ——
        //   · 昵称/头像来自当前登录态（否则可以冒别人的名发评价）
        //   · 商品名来自数据库（否则评价里可以写任意商品名）
        //   · show_status 由审核流程决定（不能让发布者自己决定"已通过审核"）
        //   · 各种计数从 0 开始，由系统维护
        // 这和秒杀接口「不接收价格」是同一条原则：客户端只能表达意图，不能表达事实。
        PmsComment comment = new PmsComment();
        comment.setProductId(param.getProductId());
        comment.setMemberId(member.getId());
        comment.setMemberNickName(member.getNickname() == null ? member.getUsername() : member.getNickname());
        comment.setMemberIcon(member.getIcon());
        comment.setProductName(product.getName());
        comment.setStar(param.getStar());
        comment.setMemberIp(null);          // 需要的话从请求上下文取，这里不伪造
        comment.setCreateTime(new Date());
        // 默认直接可见。若要接审核流程，把这里改成 0，由后台审核后置 1。
        comment.setShowStatus(SHOW_STATUS_VISIBLE);
        comment.setProductAttribute(param.getProductAttribute());
        comment.setContent(param.getContent());
        comment.setPics(param.getPics());
        comment.setCollectCouont(0);
        comment.setReadCount(0);
        comment.setReplayCount(0);

        int count = commentDao.insertComment(comment);
        LOGGER.info("会员 {} 对商品 {} 发表评价，star={}，评价ID={}",
                member.getId(), param.getProductId(), param.getStar(), comment.getId());
        return count;
    }

    @Override
    public CommonPage<PmsComment> listByProduct(Long productId, Integer pageNum, Integer pageSize) {
        PageHelper.startPage(pageNum, pageSize);
        PmsCommentExample example = new PmsCommentExample();
        // 只返回审核通过的 —— 口径必须和 summary() 一致，
        // 否则会出现"详情页说 100 条评价、列表只显示 80 条"这种自相矛盾
        example.createCriteria()
                .andProductIdEqualTo(productId)
                .andShowStatusEqualTo(SHOW_STATUS_VISIBLE);
        example.setOrderByClause("create_time desc");
        List<PmsComment> list = commentMapper.selectByExampleWithBLOBs(example);
        return CommonPage.restPage(list);
    }

    @Override
    public PmsCommentSummary summary(Long productId) {
        PmsCommentSummary summary = commentDao.selectSummary(productId);
        if (summary == null) {
            // 该商品一条评价都没有：SUM 会返回 NULL，这里兜一个全 0 的对象，
            // 免得前端拿到 null 还要额外判空
            return new PmsCommentSummary();
        }
        // 好评率现场算：放 SQL 里算也行，但那会让 SQL 更长更难读，
        // 而这里只是一次除法、成本可以忽略。原则是"简单计算留在 Java，聚合留在 SQL"。
        if (summary.getTotalCount() != null && summary.getTotalCount() > 0) {
            summary.setGoodRate(BigDecimal.valueOf(summary.getGoodCount())
                    .multiply(BigDecimal.valueOf(100))
                    .divide(BigDecimal.valueOf(summary.getTotalCount()), 1, RoundingMode.HALF_UP));
        }
        return summary;
    }

    @Override
    public List<PmsCommentReplay> listReplay(Long commentId) {
        PmsCommentReplayExample example = new PmsCommentReplayExample();
        example.createCriteria().andCommentIdEqualTo(commentId);
        example.setOrderByClause("create_time asc");
        return commentReplayMapper.selectByExample(example);
    }

    @Override
    public void incrReadCount(Long id) {
        commentDao.incrReadCount(id);
    }

    @Override
    public void incrCollectCount(Long id) {
        commentDao.incrCollectCount(id);
    }
}
