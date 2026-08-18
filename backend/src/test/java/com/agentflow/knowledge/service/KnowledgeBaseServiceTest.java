package com.agentflow.knowledge.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.agentflow.common.api.PageRequest;
import com.agentflow.common.api.PageResult;
import com.agentflow.common.error.BusinessException;
import com.agentflow.common.error.ErrorCode;
import com.agentflow.knowledge.dto.CreateKnowledgeBaseRequest;
import com.agentflow.knowledge.dto.KnowledgeBaseResponse;
import com.agentflow.knowledge.model.KnowledgeBase;
import com.agentflow.knowledge.repository.KnowledgeBaseMapper;
import com.agentflow.user.security.AuthenticatedUser;
import java.time.OffsetDateTime;
import java.util.List;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 中文：不依赖真实 PostgreSQL 的知识库 Service 测试，重点验证 owner 不能由请求体伪造、
 * 配置默认值和 owner-scoped 查询。Flyway SQL 的联调留给后面的 IDEA 手工验收。
 *
 * <p>English: Database-free knowledge-base service tests. They focus on non-forgeable
 * ownership, configuration defaults, and owner-scoped queries; Flyway SQL integration
 * is verified later through IDEA manual validation.
 */
@ExtendWith(MockitoExtension.class)
class KnowledgeBaseServiceTest {

    /**
     * 中文：真实 Mapper 启动时会建立 Lambda 属性到列名的缓存；这个单元测试把 Mapper mock 掉了，
     * 因此显式初始化同一缓存，才能检查 owner-scoped wrapper 的 SQL 片段。
     * English: A real mapper initializes the Lambda property-to-column cache at startup.
     * This unit test mocks the mapper, so initialize the same cache explicitly before
     * inspecting the owner-scoped wrapper's SQL fragment.
     */
    @BeforeAll
    static void initializeKnowledgeBaseLambdaCache() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(
                new MybatisConfiguration(),
                "KnowledgeBaseServiceTest"
        );
        assistant.setCurrentNamespace(KnowledgeBaseMapper.class.getName());
        TableInfoHelper.initTableInfo(assistant, KnowledgeBase.class);
    }

    @Mock
    private KnowledgeBaseMapper knowledgeBaseMapper;

    @InjectMocks
    private KnowledgeBaseService knowledgeBaseService;

    @Captor
    private ArgumentCaptor<KnowledgeBase> knowledgeBaseCaptor;

    @Captor
    private ArgumentCaptor<Wrapper<KnowledgeBase>> queryCaptor;

    @Test
    void shouldCreateAnOwnedKnowledgeBaseWithDefaults() {
        AuthenticatedUser currentUser = currentUser();
        CreateKnowledgeBaseRequest request = new CreateKnowledgeBaseRequest(
                "  Payment knowledge base  ",
                "  Error codes and refund rules  ",
                null,
                "  ",
                null,
                null
        );
        when(knowledgeBaseMapper.insert(any(KnowledgeBase.class))).thenAnswer(invocation -> {
            KnowledgeBase knowledgeBase = invocation.getArgument(0);
            knowledgeBase.setId(201L);
            return 1;
        });

        KnowledgeBaseResponse response = knowledgeBaseService.create(currentUser, request);

        verify(knowledgeBaseMapper).insert(knowledgeBaseCaptor.capture());
        KnowledgeBase persisted = knowledgeBaseCaptor.getValue();
        assertThat(persisted.getUserId()).isEqualTo(currentUser.id());
        assertThat(persisted.getName()).isEqualTo("Payment knowledge base");
        assertThat(persisted.getDescription()).isEqualTo("Error codes and refund rules");
        assertThat(persisted.getEmbeddingProvider()).isEqualTo("dashscope");
        assertThat(persisted.getEmbeddingModel()).isEqualTo("text-embedding-v4");
        assertThat(persisted.getChunkSize()).isEqualTo(800);
        assertThat(persisted.getChunkOverlap()).isEqualTo(120);
        assertThat(persisted.getStatus()).isEqualTo("ACTIVE");
        assertThat(persisted.getCreatedAt()).isNotNull();
        assertThat(persisted.getUpdatedAt()).isEqualTo(persisted.getCreatedAt());
        assertThat(response.id()).isEqualTo("201");
        assertThat(response.createdAt()).isEqualTo(persisted.getCreatedAt());
    }

    @Test
    void shouldRejectAnOverlapThatIsNotSmallerThanTheEffectiveChunkSize() {
        CreateKnowledgeBaseRequest request = new CreateKnowledgeBaseRequest(
                "Payment knowledge base",
                null,
                null,
                null,
                100,
                100
        );

        assertThatThrownBy(() -> knowledgeBaseService.create(currentUser(), request))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.COMMON_PARAM_INVALID));

        verify(knowledgeBaseMapper, never()).insert(any(KnowledgeBase.class));
    }

    @Test
    void shouldReturnOnlyTheCurrentOwnersNonDeletedPageIncludingDisabledItems() {
        AuthenticatedUser currentUser = currentUser();
        when(knowledgeBaseMapper.selectPage(
                org.mockito.ArgumentMatchers.<IPage<KnowledgeBase>>any(),
                org.mockito.ArgumentMatchers.<Wrapper<KnowledgeBase>>any()
        )).thenAnswer(invocation -> {
            IPage<KnowledgeBase> page = invocation.getArgument(0);
            page.setRecords(List.of(
                    knowledgeBase(301L, "Newest", "ACTIVE"),
                    knowledgeBase(300L, "Disabled but still manageable", "DISABLED")
            ));
            page.setTotal(3L);
            return page;
        });

        PageResult<KnowledgeBaseResponse> result = knowledgeBaseService.listOwnedBy(
                currentUser,
                new PageRequest(1, 2)
        );

        verify(knowledgeBaseMapper).selectPage(
                org.mockito.ArgumentMatchers.<IPage<KnowledgeBase>>any(),
                queryCaptor.capture()
        );
        assertThat(queryCaptor.getValue().getSqlSegment())
                .contains("user_id", "deleted_at", "created_at", "id");
        assertThat(result.getItems()).extracting(KnowledgeBaseResponse::id)
                .containsExactly("301", "300");
        assertThat(result.getItems()).extracting(KnowledgeBaseResponse::status)
                .containsExactly("ACTIVE", "DISABLED");
        assertThat(result.getPage()).isEqualTo(1);
        assertThat(result.getPageSize()).isEqualTo(2);
        assertThat(result.getTotal()).isEqualTo(3L);
        assertThat(result.isHasNext()).isTrue();
    }

    @Test
    void shouldFailFastWhenTheMapperDoesNotInsertExactlyOneRow() {
        when(knowledgeBaseMapper.insert(any(KnowledgeBase.class))).thenReturn(0);

        assertThatThrownBy(() -> knowledgeBaseService.create(
                currentUser(),
                new CreateKnowledgeBaseRequest("Payment knowledge base", null, null, null, null, null)
        )).isInstanceOf(IllegalStateException.class)
                .hasMessage("Expected exactly one inserted knowledge_base row");
    }

    private static AuthenticatedUser currentUser() {
        return new AuthenticatedUser(101L, "xavier_01", "Xavier", "USER");
    }

    private static KnowledgeBase knowledgeBase(Long id, String name, String status) {
        OffsetDateTime now = OffsetDateTime.parse("2026-08-06T12:00:00+08:00");
        KnowledgeBase knowledgeBase = new KnowledgeBase();
        knowledgeBase.setId(id);
        knowledgeBase.setUserId(101L);
        knowledgeBase.setName(name);
        knowledgeBase.setEmbeddingProvider("dashscope");
        knowledgeBase.setEmbeddingModel("text-embedding-v4");
        knowledgeBase.setChunkSize(800);
        knowledgeBase.setChunkOverlap(120);
        knowledgeBase.setStatus(status);
        knowledgeBase.setCreatedAt(now);
        knowledgeBase.setUpdatedAt(now);
        return knowledgeBase;
    }
}
