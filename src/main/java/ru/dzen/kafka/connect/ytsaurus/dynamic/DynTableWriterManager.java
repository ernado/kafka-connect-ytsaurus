package ru.dzen.kafka.connect.ytsaurus.dynamic;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.apache.kafka.connect.errors.RetriableException;
import ru.dzen.kafka.connect.ytsaurus.common.TableWriterManager;
import ru.dzen.kafka.connect.ytsaurus.common.UnstructuredTableSchema;
import tech.ytsaurus.client.YTsaurusClient;
import tech.ytsaurus.client.request.CreateNode;
import tech.ytsaurus.client.request.MountTable;
import tech.ytsaurus.client.request.ReshardTable;
import tech.ytsaurus.core.cypress.CypressNodeType;
import tech.ytsaurus.core.cypress.YPath;
import tech.ytsaurus.core.tables.TableSchema;
import tech.ytsaurus.ysontree.YTree;
import tech.ytsaurus.ysontree.YTreeNode;

public class DynTableWriterManager extends DynTableWriter implements TableWriterManager {

  public DynTableWriterManager(DynTableWriterConfig config) {
    super(config);
  }


  protected void createDynamicTable(YTsaurusClient client, YPath path, TableSchema schema)
      throws Exception {
    var createNodeBuilder = CreateNode.builder().setPath(path)
        .setType(CypressNodeType.TABLE).setAttributes(Map.of(
            "dynamic", YTree.booleanNode(true),
            "schema", schema.toYTree(),
            "enable_dynamic_store_read", YTree.booleanNode(true)
        )).setIgnoreExisting(true);
    client.createNode(createNodeBuilder.build()).get();
    log.info(String.format("Created table %s", path));
  }

  protected void mountDynamicTable(YTsaurusClient client, YPath path) throws Exception {
    var attributes = client.getNode(path + "/@").get().mapNode();
    var currentTabletState = attributes.get("tablet_state").map(YTreeNode::stringValue);
    if (currentTabletState.equals(Optional.of("mounted"))) {
      log.info("Table %s is already mounted", path);
      return;
    }
    log.info("Trying to mount table %s", path);
    client.mountTableAndWaitTablets(new MountTable(path)).get();
    log.info("Mounted table %s", path);
  }

  protected void reshardQueueAndSetAttributesIfNeeded(YTsaurusClient client) throws Exception {
    var attributes = client.getNode(config.getDataQueueTablePath() + "/@").get().mapNode();
    for (var entry : config.getExtraQueueAttributes().entrySet()) {
      if (!Objects.equals(attributes.get(entry.getKey()), Optional.of(entry.getValue()))) {
        client.setNode(config.getDataQueueTablePath() + "/@" + entry.getKey(), entry.getValue());
        log.info(
            String.format("Changed %s/@%s to %s", config.getDataQueueTablePath(), entry.getKey(),
                entry.getValue()));
      }
    }
    var currentTabletCount = attributes.get("tablet_count").map(YTreeNode::intValue);
    if (currentTabletCount.equals(Optional.of(config.getTabletCount()))) {
      log.info(String.format("No need to reshard table %s: tablet count = desired tablet count",
          config.getDataQueueTablePath()));
      return;
    }
    var currentTabletState = attributes.get("tablet_state")
        .map(YTreeNode::stringValue);
    if (currentTabletState.equals(Optional.of("mounted"))) {
      log.info(String.format("Unmounting table %s", config.getDataQueueTablePath()));
      client.unmountTableAndWaitTablets(config.getDataQueueTablePath().toString()).join();
      log.info(String.format("Unmounted table %s", config.getDataQueueTablePath()));
    }
    log.info(String.format("Resharding table %s", config.getDataQueueTablePath()));
    client.reshardTable(ReshardTable.builder().setPath(config.getDataQueueTablePath())
        .setTabletCount(config.getTabletCount()).build()).get();
    log.info(String.format("Successfully resharded table %s", config.getDataQueueTablePath()));
  }

  @Override
  public void start() throws RetriableException {
    if (!config.getAutoCreateTables()) {
      return;
    }
    try {
      var createNodeBuilder = CreateNode.builder()
          .setPath(config.getMetadataDirectory())
          .setType(CypressNodeType.MAP)
          .setRecursive(true)
          .setIgnoreExisting(true);
      client.createNode(createNodeBuilder.build()).get();
    } catch (Exception e) {
      throw new RetriableException(e);
    }
    try {
      var dataQueueTableSchema = UnstructuredTableSchema.createDataQueueTableSchema(
          config.getKeyOutputFormat(), config.getValueOutputFormat());
      createDynamicTable(client, config.getDataQueueTablePath(), dataQueueTableSchema);
      reshardQueueAndSetAttributesIfNeeded(client);
      mountDynamicTable(client, config.getDataQueueTablePath());
      createDynamicTable(client, config.getOffsetsTablePath(),
          UnstructuredTableSchema.offsetsTableSchema);
      mountDynamicTable(client, config.getOffsetsTablePath());
    } catch (Exception ex) {
      log.warn("Cannot initialize queue", ex);
      throw new RetriableException(ex);
    }
  }

  @Override
  public void stop() {

  }
}
