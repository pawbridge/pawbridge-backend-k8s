package com.pawbridge.animalservice.repository;

import com.pawbridge.animalservice.entity.Shelter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.test.context.ActiveProfiles;
import static org.assertj.core.api.Assertions.assertThat;

@org.springframework.context.annotation.Import(ShelterSearchRepositoryTest.AuditingConfiguration.class)
@DataJpaTest
@ActiveProfiles("ci")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class ShelterSearchRepositoryTest {
    @org.springframework.boot.test.context.TestConfiguration(proxyBeanMethods = false)
    @org.springframework.data.jpa.repository.config.EnableJpaAuditing
    static class AuditingConfiguration {}

    @Autowired TestEntityManager em;
    @Autowired ShelterRepository repository;

    @Test void givenNameAndAddressMatchesAcrossRegions__whenCombinedSearch__thenOnlySelectedRegionAndCorrectPageCount() {
        var byName = save("test-combo-1", "나눔 센터", "서울 마포구");
        var byAddress = save("test-combo-2", "행복 쉼터", "서울 센터로");
        save("test-combo-3", "센터", "부산 해운대구");
        save("test-combo-4", "쉼터", "부산 센터로");
        save("test-combo-5", "쉼터", "서울 강남구");
        save("test-combo-6", "센터", null);
        var first = repository.findByKeywordAndAddress("센터", "서울", PageRequest.of(0,1,Sort.by("name")));
        var second = repository.findByKeywordAndAddress("센터", "서울", PageRequest.of(1,1,Sort.by("name")));
        assertThat(first.getTotalElements()).isEqualTo(2);
        assertThat(first.getTotalPages()).isEqualTo(2);
        assertThat(first.getContent()).extracting(Shelter::getId).containsExactly(byName.getId());
        assertThat(second.getContent()).extracting(Shelter::getId).containsExactly(byAddress.getId());
    }

    private Shelter save(String reg, String name, String address) {
        return em.persistAndFlush(Shelter.builder().careRegNo(reg).name(name).address(address).build());
    }
}
