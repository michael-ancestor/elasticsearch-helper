package org.xbib.elasticsearch.support.various;

import org.elasticsearch.client.Client;
import org.elasticsearch.common.logging.ESLogger;
import org.elasticsearch.common.logging.ESLoggerFactory;
import org.junit.Test;

import static org.elasticsearch.client.Requests.deleteIndexRequest;
import static org.elasticsearch.client.Requests.indexRequest;
import static org.elasticsearch.common.settings.ImmutableSettings.settingsBuilder;
import static org.elasticsearch.common.xcontent.XContentFactory.jsonBuilder;
import static org.elasticsearch.index.query.QueryBuilders.matchAllQuery;
import static org.elasticsearch.node.NodeBuilder.nodeBuilder;

public class SimpleTest {

    private final static ESLogger logger = ESLoggerFactory.getLogger(SimpleTest.class.getName());

    @Test
    public void test() throws Exception {
        String INDEX = "test";
        String TYPE = "test";
        String FIELD = "field";
        Client client = nodeBuilder()
                .settings(settingsBuilder()
                        .put("cluster.name", "test")
                        .put("index.analysis.analyzer.default.filter.0", "lowercase")
                        .put("index.analysis.analyzer.default.filter.1", "trim")
                        .put("index.analysis.analyzer.default.tokenizer", "keyword"))
                .local(true).node().client();
        try {
            client.admin().indices().delete(deleteIndexRequest(INDEX)).actionGet();
        } catch (Exception e) {
            // ignore
        }
        client.index(indexRequest()
                .index(INDEX).type(TYPE).id("1")
                .source(jsonBuilder().startObject().field(FIELD, "1%2fPJJP3JV2C24iDfEu9XpHBaYxXh%2fdHTbmchB35SDznXO2g8Vz4D7GTIvY54iMiX_149c95f02a8").endObject())
                .refresh(true)).actionGet();
        String doc = client.prepareSearch(INDEX).setTypes(TYPE)
                .setQuery(matchAllQuery())
                .execute().actionGet().getHits().getAt(0).getSourceAsString();

        logger.info("{}", doc);
    }
}