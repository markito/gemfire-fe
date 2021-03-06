package io.pivotal.bds.gemfire.locking;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.gemstone.gemfire.cache.Cache;
import com.gemstone.gemfire.cache.CacheFactory;
import com.gemstone.gemfire.cache.Region;
import com.gemstone.gemfire.cache.RegionFactory;
import com.gemstone.gemfire.cache.RegionShortcut;
import com.gemstone.gemfire.cache.execute.FunctionService;
import com.gemstone.gemfire.cache.server.CacheServer;

public class Main {

    private static final Logger LOG = LoggerFactory.getLogger(Main.class);

    public static void main(String[] args) throws Exception {
        LOG.info("creating Cache");

        CacheFactory cf = new CacheFactory();
        cf.set("locators", "localhost[10334]");
        Cache c = cf.create();

        LOG.info("creating Regions");

        RegionFactory<LockerKey<String>, Locker> lrf = c.createRegionFactory(RegionShortcut.LOCAL);
        lrf.setCacheLoader(new LockerCacheLoader<>());
        Region<LockerKey<String>, Locker> lr = lrf.create("Locker");

        RegionFactory<String, Integer> crf = c.createRegionFactory(RegionShortcut.PARTITION);
        crf.setCacheLoader(new CounterCacheLoader());
        Region<String, Integer> cr = crf.create("Counter");

        LOG.info("registering Function");

        LockingFunction lockingFunction = new LockingFunction(cr, lr);
        FunctionService.registerFunction(lockingFunction);

        LOG.info("creating CacheServer");

        CacheServer cs = c.addCacheServer();
        cs.setPort(40404);
        cs.start();

        LOG.info("ready");
    }
}
