package cn.hashdata.dbsync;

import java.sql.Connection;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map.Entry;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.apache.commons.daemon.DaemonController;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.commons.lang3.tuple.Triple;
import org.apache.commons.pool2.impl.GenericObjectPool;
import org.apache.commons.pool2.impl.GenericObjectPoolConfig;

import com.codahale.metrics.Gauge;
import com.codahale.metrics.MetricRegistry;

import cn.hashdata.dbsync.ChangeSet.ChangeSetFactory;
import cn.hashdata.dbsync.Row.RowArrayFactory;
import cn.hashdata.dbsync.Row.RowFactory;
import cn.hashdata.dbsync.RowSet.RowSetFactory;

/**
 * dbsync context.
 *
 * @author yuze
 *
 */
public class Context {
  static final protected Long TIMEOUT_MS = 1000L;
  static final protected int AUX_THREAD = 3;

  public volatile boolean stop = false;

  public Config conf;
  public MetricRegistry metrics = new MetricRegistry();

  public HashMap<String, String> tableMap;
  public HashMap<String, Table> tablesInfo;

  public LinkedBlockingQueue<ChangeSet> changeSetQueue;
  public ConcurrentHashMap<String, RowCache> tableRowCache;
  public HashMap<String, ChangeLoader> changeLoaders;
  public LinkedBlockingQueue<Connection> loaderConnections;
  public HashMap<Connection, HashSet<String>> temporaryTables;
  LinkedBlockingQueue<Triple<String, CommitCallback, String>> positionUpdateQueue;
  public ConcurrentHashMap<String, Pair<CommitCallback, String>> bookkeeping;

  public GenericObjectPool<ChangeSet> idleChangeSets;
  public GenericObjectPool<RowSet> idleRowSets;
  public GenericObjectPool<Row> idleRows;
  public GenericObjectPool<ArrayList<Row>> idleRowArrays;

  public ExecutorService threadPool;
  public CompletionService<Long> cs;
  public ExecutorService loaderThreadPool;
  public CompletionService<Long> loadercs;

  public int exitLoaders;
  public WatchDog watchDog;

  static class WatchDog extends Thread {
    private DaemonController controller;
    private Context cxt;

    public WatchDog(DaemonController controller, Context cxt) {
      this.controller = controller;
      this.cxt = cxt;
      this.setDaemon(true);
      this.setName("WatchDog");
    }

    @Override
    public void run() {
      try {
        cxt.waitForComplete(false);
      } catch (InterruptedException e) {
        controller.fail("Service stopped by user");
      } catch (Exception e) {
        controller.fail(e);
      }
    }
  }

  /**
   * Create a new dbsync context.
   *
   * @param conf dbsync configuration
   */
  public Context(Config conf) {
    this(conf, false);
  }

  /**
   * Create a new dbsync context for test.
   *
   * @param conf dbsync configuration
   * @param test unitest or not
   */
  public Context(Config conf, Boolean test) {
    this.conf = conf;

    this.tableMap = conf.tableMap;
    this.tablesInfo = new HashMap<String, Table>();

    this.changeSetQueue = new LinkedBlockingQueue<ChangeSet>(conf.changeset_queue_size);
    this.tableRowCache = new ConcurrentHashMap<String, RowCache>();
    initRowCache();

    this.changeLoaders = new HashMap<String, ChangeLoader>();
    this.loaderConnections = new LinkedBlockingQueue<Connection>(conf.loader_conn_size);
    this.temporaryTables = new HashMap<Connection, HashSet<String>>();

    this.positionUpdateQueue = new LinkedBlockingQueue<Triple<String, CommitCallback, String>>();
    this.bookkeeping = new ConcurrentHashMap<String, Pair<CommitCallback, String>>();

    exitLoaders = 0;

    createObjectPool();

    if (!test) {
      createThreadPool();
      registerMetric();
    }
  }

  private void initRowCache() {
    for (String fullTableName : tableMap.values()) {
      if (tableRowCache.containsKey(fullTableName)) {
        continue;
      }

      RowCache rowCache = new RowCache(this, fullTableName);
      tableRowCache.put(fullTableName, rowCache);
    }
  }

  private void createObjectPool() {
    GenericObjectPoolConfig config = new GenericObjectPoolConfig();
    config.setBlockWhenExhausted(false);
    config.setMaxTotal(-1);
    config.setMaxIdle(-1);
    config.setMinIdle(0);
    config.setMaxWaitMillis(-1);

    config.setJmxNamePrefix("idleChangeSets");
    idleChangeSets = new GenericObjectPool<ChangeSet>(new ChangeSetFactory(), config);
    config.setJmxNamePrefix("idleRowSets");
    idleRowSets = new GenericObjectPool<RowSet>(new RowSetFactory(), config);
    config.setJmxNamePrefix("idleRows");
    idleRows = new GenericObjectPool<Row>(new RowFactory(), config);
    config.setJmxNamePrefix("idleRowArrays");
    idleRowArrays = new GenericObjectPool<ArrayList<Row>>(new RowArrayFactory(), config);
  }

  private void createThreadPool() {
    // #provider + #change set dispatcher + # task generator
    threadPool = Executors.newFixedThreadPool(conf.dataSource.size() + AUX_THREAD);
    cs = new ExecutorCompletionService<Long>(threadPool);

    // #loader
    loaderThreadPool = Executors.newFixedThreadPool(conf.loadersCount);
    loadercs = new ExecutorCompletionService<Long>(loaderThreadPool);
  }

  private void registerMetric() {
    metrics.register(MetricRegistry.name(Context.class, "ChangeSetQueue"), new Gauge<Integer>() {
      @Override
      public Integer getValue() {
        return changeSetQueue.size();
      }
    });

    for (Entry<String, RowCache> entry : tableRowCache.entrySet()) {
      String fullTableName = entry.getKey();
      RowCache rowCache = entry.getValue();
      metrics.register(
          MetricRegistry.name(RowCache.class, "for " + fullTableName), new Gauge<Integer>() {
            @Override
            public Integer getValue() {
              return rowCache.rows.size();
            }
          });
    }

    metrics.register(
        MetricRegistry.name(GenericObjectPool.class, "for ChangeSet"), new Gauge<Integer>() {
          @Override
          public Integer getValue() {
            return idleChangeSets.getNumActive() + idleChangeSets.getNumIdle();
          }
        });
    metrics.register(
        MetricRegistry.name(GenericObjectPool.class, "for RowSet"), new Gauge<Integer>() {
          @Override
          public Integer getValue() {
            return idleRowSets.getNumActive() + idleRowSets.getNumIdle();
          }
        });
    metrics.register(MetricRegistry.name(GenericObjectPool.class, "for Row"), new Gauge<Integer>() {
      @Override
      public Integer getValue() {
        return idleRows.getNumActive() + idleRows.getNumIdle();
      }
    });
    metrics.register(
        MetricRegistry.name(GenericObjectPool.class, "for RowArray"), new Gauge<Integer>() {
          @Override
          public Integer getValue() {
            return idleRowArrays.getNumActive() + idleRowArrays.getNumIdle();
          }
        });
  }

  public void startWatchDog(DaemonController controller) {
    watchDog = new WatchDog(controller, this);
    watchDog.start();
  }

  /**
   * Wait for all threads to exit.
   *
   * @param ignoreError whether to ignore error.
   * @throws InterruptedException - if interrupted while waiting
   * @throws DbsyncException - Wrap and throw Exception which cannot be handled
   */
  public void waitForComplete(boolean ignoreError) throws DbsyncException, InterruptedException {
    while (!threadPool.isTerminated() && !loaderThreadPool.isTerminated()) {
      Throwable cause = null;

      if (!threadPool.isTerminated()) {
        Future<Long> result = cs.poll(TIMEOUT_MS, TimeUnit.MILLISECONDS);

        if (result != null) {
          try {
            result.get();
          } catch (ExecutionException e) {
            cause = e.getCause();
          }
        }
      }

      if (cause != null && !ignoreError) {
        try {
          throw cause;
        } catch (DbsyncException | InterruptedException | RuntimeException e) {
          throw e;
        } catch (Throwable e) {
          throw new DbsyncException(e.getMessage(), e);
        }
      }

      if (!loaderThreadPool.isTerminated()) {
        Future<Long> loaderResult = loadercs.poll(TIMEOUT_MS, TimeUnit.MILLISECONDS);

        if (loaderResult != null) {
          try {
            loaderResult.get();
          } catch (ExecutionException e) {
            cause = e.getCause();
          } finally {
            exitLoaders++;
          }

          if (exitLoaders == conf.loadersCount) {
            for (Connection conn : loaderConnections) {
              try {
                conn.close();
              } catch (Exception ignore) {
              }
            }

            loaderConnections.clear();

            if (!stop) {
              throw new DbsyncException("All loaders failed.");
            }
          }
        }

        if (cause != null && !ignoreError) {
          try {
            throw cause;
          } catch (DbsyncException | InterruptedException | RuntimeException e) {
            throw e;
          } catch (Throwable e) {
            throw new DbsyncException(e.getMessage(), e);
          }
        }
      }
    }
  }
}
