package ai.codecraft.modules.skills.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ai.codecraft.modules.skills.config.SkillJsonConfiguration;
import ai.codecraft.modules.skills.config.SkillProperties;
import ai.codecraft.modules.skills.model.dto.SkillFileDTO;
import ai.codecraft.modules.skills.model.dto.SkillPackageRequest;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

class SkillPackageReadTest {
    @Test
    void readsValidRetainedPackagesLargerThanTheGeneralSmallJsonBoundary() throws Exception {
        String content = "---\nname: a\ndescription: d\n---\n" + "x".repeat(6000);
        var payload = new ObjectMapper().writeValueAsString(new SkillPackageRequest(1, List.of(new SkillFileDTO("SKILL.md", content))));
        var store = mock(SkillStore.class); when(store.contents("session", "id")).thenReturn(payload);
        try (var context = new AnnotationConfigApplicationContext()) {
            context.registerBean("applicationJson", ObjectMapper.class, () -> {
                var mapper = new ObjectMapper();
                mapper.getFactory().setStreamReadConstraints(StreamReadConstraints.builder().maxStringLength(4096).maxDocumentLength(16384).build());
                return mapper;
            });
            context.registerBean(SkillProperties.class, () -> new SkillProperties(50, 262144, 65536, 5242880, 32, 256, 4));
            context.registerBean(SkillStore.class, () -> store);
            context.register(SkillJsonConfiguration.class, SkillPackageParser.class, SkillService.class); context.refresh();
            assertThat(context.getBean(SkillService.class).contents("session", "id").files().getFirst().content()).isEqualTo(content);
        }
    }
}
