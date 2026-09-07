package com.pawbridge.communityservice.elasticsearch;

import org.junit.jupiter.api.Test;
import org.springframework.data.elasticsearch.annotations.Document;
import org.springframework.data.elasticsearch.annotations.Setting;

import static org.assertj.core.api.Assertions.assertThat;

class PostDocumentIndexSettingsTest {

    @Test
    void givenSingleNodeCluster__whenCreatingPostsIndex__thenDoNotRequestReplicaShard() {
        Document document = PostDocument.class.getAnnotation(Document.class);
        Setting setting = PostDocument.class.getAnnotation(Setting.class);

        assertThat(document).isNotNull();
        assertThat(document.indexName()).isEqualTo("posts");
        assertThat(setting).isNotNull();
        assertThat(setting.shards()).isEqualTo((short) 1);
        assertThat(setting.replicas()).isEqualTo((short) 0);
    }
}
