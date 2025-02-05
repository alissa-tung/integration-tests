package io.hstream.testing;

import static io.hstream.testing.TestUtils.makeClient;
import static io.hstream.testing.TestUtils.makeHServer;
import static io.hstream.testing.TestUtils.makeHStore;
import static io.hstream.testing.TestUtils.makeZooKeeper;
import static io.hstream.testing.TestUtils.printBeginFlag;
import static io.hstream.testing.TestUtils.printEndFlag;
import static io.hstream.testing.TestUtils.silence;
import static io.hstream.testing.TestUtils.writeLog;

import io.hstream.HStreamClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.GenericContainer;

public class ClusterExtension implements BeforeEachCallback, AfterEachCallback {

  static final int CLUSTER_SIZE = 3;
  private static final Logger logger = LoggerFactory.getLogger(ClusterExtension.class);
  private final List<GenericContainer<?>> hServers = new ArrayList<>(CLUSTER_SIZE);
  private final List<String> hServerUrls = new ArrayList<>(CLUSTER_SIZE);
  private Path dataDir;
  private GenericContainer<?> zk;
  private GenericContainer<?> hstore;
  private String grp;
  private long beginTime;

  private HStreamClient client;

  @Override
  public void beforeEach(ExtensionContext context) throws Exception {
    beginTime = System.currentTimeMillis();

    grp = UUID.randomUUID().toString();
    printBeginFlag(context);

    dataDir = Files.createTempDirectory("hstream");

    TestUtils.SecurityOptions securityOptions = makeSecurityOptions(context.getTags());

    zk = makeZooKeeper();
    zk.start();
    String zkHost = "127.0.0.1";
    logger.debug("zkHost: " + zkHost);

    hstore = makeHStore(dataDir);
    hstore.start();
    String hstoreHost = "127.0.0.1";
    logger.debug("hstoreHost: " + hstoreHost);

    String hServerAddress = "127.0.0.1";
    for (int i = 0; i < CLUSTER_SIZE; ++i) {
      int hServerPort = 6570 + i;
      int hServerInnerPort = 65000 + i;
      var hServer =
          makeHServer(
              hServerAddress,
              hServerPort,
              hServerInnerPort,
              dataDir,
              zkHost,
              hstoreHost,
              i,
              securityOptions);
      hServer.start();
      hServers.add(hServer);
      hServerUrls.add(hServerAddress + ":" + hServerPort);
    }
    Thread.sleep(3000);

    Object testInstance = context.getRequiredTestInstance();
    var initUrl = hServerUrls.stream().reduce((url1, url2) -> url1 + "," + url2).get();
    silence(
        () ->
            testInstance
                .getClass()
                .getMethod("setHStreamDBUrl", String.class)
                .invoke(testInstance, initUrl));

    client = makeClient(initUrl, context.getTags());
    silence(
        () ->
            testInstance
                .getClass()
                .getMethod("setClient", HStreamClient.class)
                .invoke(testInstance, client));
    silence(
        () ->
            testInstance
                .getClass()
                .getMethod("setHServers", List.class)
                .invoke(testInstance, hServers));
    silence(
        () ->
            testInstance
                .getClass()
                .getMethod("setHServerUrls", List.class)
                .invoke(testInstance, hServerUrls));

    silence(
        () ->
            testInstance
                .getClass()
                .getMethod("setLogMsgPathPrefix", String.class)
                .invoke(testInstance, grp));

    silence(
        () ->
            testInstance
                .getClass()
                .getMethod("setExtensionContext", ExtensionContext.class)
                .invoke(testInstance, context));
  }

  @Override
  public void afterEach(ExtensionContext context) throws Exception {
    Thread.sleep(100);

    client.close();
    // waiting for servers to flush logs
    for (int i = 0; i < hServers.size(); i++) {
      var hServer = hServers.get(i);
      writeLog(context, "hserver-" + i, grp, hServer.getLogs());
      hServer.close();
    }

    hServers.clear();
    hServerUrls.clear();

    writeLog(context, "hstore", grp, hstore.getLogs());
    hstore.close();
    writeLog(context, "zk", grp, zk.getLogs());
    zk.close();

    logger.info("total time is = {}ms", System.currentTimeMillis() - beginTime);
    printEndFlag(context);
  }

  TestUtils.SecurityOptions makeSecurityOptions(Set<String> tags) {
    TestUtils.SecurityOptions options = new TestUtils.SecurityOptions();
    options.dir = getClass().getClassLoader().getResource("security").getPath();
    if (tags.contains("tls")) {
      options.enableTls = true;
      options.keyPath = "/data/security/server.key.pem";
      options.certPath = "/data/security/signed.server.cert.pem";
    }
    if (tags.contains("tls-authentication")) {
      options.caPath = "/data/security/ca.cert.pem";
    }
    return options;
  }
}
