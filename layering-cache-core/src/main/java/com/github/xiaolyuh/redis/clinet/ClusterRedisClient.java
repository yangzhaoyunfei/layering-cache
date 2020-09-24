package com.github.xiaolyuh.redis.clinet;

import com.alibaba.fastjson.JSON;
import com.github.xiaolyuh.listener.RedisMessageListener;
import com.github.xiaolyuh.redis.command.TencentScan;
import com.github.xiaolyuh.redis.serializer.KryoRedisSerializer;
import com.github.xiaolyuh.redis.serializer.RedisSerializer;
import com.github.xiaolyuh.redis.serializer.SerializationException;
import com.github.xiaolyuh.redis.serializer.StringRedisSerializer;
import com.github.xiaolyuh.util.StringUtils;
import io.lettuce.core.*;
import io.lettuce.core.cluster.RedisClusterClient;
import io.lettuce.core.cluster.api.StatefulRedisClusterConnection;
import io.lettuce.core.cluster.api.sync.RedisClusterCommands;
import io.lettuce.core.codec.ByteArrayCodec;
import io.lettuce.core.dynamic.RedisCommandFactory;
import io.lettuce.core.internal.HostAndPort;
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection;
import io.lettuce.core.pubsub.api.sync.RedisPubSubCommands;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.CollectionUtils;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * 集群版redis缓存
 *
 * @author olafwang
 */
public class ClusterRedisClient implements RedisClient {
    Logger logger = LoggerFactory.getLogger(ClusterRedisClient.class);

    /**
     * 腾讯云集群版异常信息
     */
    private static final String INVALID_NODE_MES = "ERR invalid node";

    /**
     * 是否是腾讯云集群版
     */
    private static volatile boolean tencentRedis = false;

    /**
     * 默认key序列化方式
     */
    private RedisSerializer keySerializer = new StringRedisSerializer();

    /**
     * 默认value序列化方式
     */
    private RedisSerializer valueSerializer = new KryoRedisSerializer(Object.class);

    private RedisClusterClient cluster;

    private StatefulRedisClusterConnection<byte[], byte[]> connection;

    private StatefulRedisPubSubConnection<String, String> pubSubConnection;

    public ClusterRedisClient(RedisProperties properties) {
        logger.info("layering-cache redis配置" + JSON.toJSONString(properties));

        String cluster = properties.getCluster();
        String[] parts = cluster.split("\\,");
        List<RedisURI> redisURIs = new ArrayList<>(parts.length);

        for (String part : parts) {
            HostAndPort hostAndPort = HostAndPort.parse(part);
            RedisURI nodeUri = RedisURI.create(hostAndPort.getHostText(), hostAndPort.hasPort() ? hostAndPort.getPort() : 6379);
            if (StringUtils.isNotBlank(properties.getPassword())) {
                nodeUri.setPassword(properties.getPassword());
            }
            redisURIs.add(nodeUri);
        }

        this.cluster = RedisClusterClient.create(redisURIs);
        this.connection = this.cluster.connect(new ByteArrayCodec());
        this.pubSubConnection = this.cluster.connectPubSub();
    }


    @Override
    public Object get(String key) {
        try {
            RedisClusterCommands<byte[], byte[]> sync = connection.sync();
            return getValueSerializer().deserialize(sync.get(getKeySerializer().serialize(key)));
        } catch (SerializationException e) {
            throw e;
        } catch (Exception e) {
            throw new RedisClientException(e.getMessage(), e);
        }
    }

    @Override
    public <T> T get(String key, Class<T> t) {
        return (T) get(key);
    }

    @Override
    public String set(String key, Object value) {

        try {
            RedisClusterCommands<byte[], byte[]> sync = connection.sync();
            return sync.set(getKeySerializer().serialize(key), getValueSerializer().serialize(value));
        } catch (SerializationException e) {
            throw e;
        } catch (Exception e) {
            throw new RedisClientException(e.getMessage(), e);
        }
    }

    @Override
    public String set(String key, Object value, long time, TimeUnit unit) {

        try {
            RedisClusterCommands<byte[], byte[]> sync = connection.sync();
            return sync.setex(getKeySerializer().serialize(key), unit.toSeconds(time), getValueSerializer().serialize(value));
        } catch (SerializationException e) {
            throw e;
        } catch (Exception e) {
            throw new RedisClientException(e.getMessage(), e);
        }
    }

    @Override
    public String setNxEx(String key, Object value, long time) {

        try {
            RedisClusterCommands<byte[], byte[]> sync = connection.sync();
            return sync.set(getKeySerializer().serialize(key), getValueSerializer().serialize(value), SetArgs.Builder.nx().ex(time));
        } catch (SerializationException e) {
            throw e;
        } catch (Exception e) {
            throw new RedisClientException(e.getMessage(), e);
        }
    }

    @Override
    public Long delete(String... keys) {
        if (Objects.isNull(keys) || keys.length == 0) {
            return 0L;
        }
        try {
            RedisClusterCommands<byte[], byte[]> sync = connection.sync();

            final byte[][] bkeys = new byte[keys.length][];
            for (int i = 0; i < keys.length; i++) {
                bkeys[i] = getKeySerializer().serialize(keys[i]);
            }
            return sync.del(bkeys);
        } catch (SerializationException e) {
            throw e;
        } catch (Exception e) {
            throw new RedisClientException(e.getMessage(), e);
        }
    }


    @Override
    public Long delete(Set<String> keys) {

        return delete(keys.toArray(new String[0]));
    }

    @Override
    public Boolean hasKey(String key) {

        try {
            RedisClusterCommands<byte[], byte[]> sync = connection.sync();
            return sync.exists(getKeySerializer().serialize(key)) > 0;
        } catch (SerializationException e) {
            throw e;
        } catch (Exception e) {
            throw new RedisClientException(e.getMessage(), e);
        }
    }

    @Override
    public Boolean expire(String key, long timeout, TimeUnit timeUnit) {
        try {
            RedisClusterCommands<byte[], byte[]> sync = connection.sync();
            return sync.expire(getKeySerializer().serialize(key), timeUnit.toSeconds(timeout));
        } catch (SerializationException e) {
            throw e;
        } catch (Exception e) {
            throw new RedisClientException(e.getMessage(), e);
        }
    }

    @Override
    public Long getExpire(String key) {
        try {
            RedisClusterCommands<byte[], byte[]> sync = connection.sync();
            return sync.ttl(getKeySerializer().serialize(key));
        } catch (SerializationException e) {
            throw e;
        } catch (Exception e) {
            throw new RedisClientException(e.getMessage(), e);
        }
    }

    @Override
    public Set<String> scan(String pattern) {
        Set<String> keys = new HashSet<>();
        // 腾讯云版本redis
        if (tencentRedis) {
            try {
                RedisClusterCommands<byte[], byte[]> sync = connection.sync();

                String nodeStr = sync.clusterNodes();
                String[] nodes = nodeStr.split("\n");
                for (String node : nodes) {
                    if (!node.contains("master")) {
                        continue;
                    }
                    long cursor = 0L;
                    do {
                        // 2150b1d23fc132cb6ff5a9553f5f1af9f19b0cc2 127.0.0.1:6379@13357 master - 0 1600342826089 2 connected 10923-16383
                        String nodeId = node.split(" ")[0];
                        RedisCommandFactory factory = new RedisCommandFactory(connection);
                        TencentScan commands = factory.getCommands(TencentScan.class);
                        List<Object> objects = commands.scan(cursor, pattern, 10000, nodeId);

                        if (CollectionUtils.isEmpty(objects)) {
                            break;
                        }
                        // 更新游标位
                        cursor = Long.parseLong((String) objects.get(0));
                        // 暂存key
                        if (objects.size() == 2) {
                            keys.addAll((ArrayList) objects.get(1));
                        }
                    } while (cursor != 0);
                }
            } catch (SerializationException e) {
                throw e;
            } catch (Exception e) {
                throw new RedisClientException(e.getMessage(), e);
            }

            return keys;
        }

        // 普通redis
        try {
            RedisClusterCommands<byte[], byte[]> sync = connection.sync();
            boolean finished;
            ScanCursor cursor = ScanCursor.INITIAL;
            do {
                KeyScanCursor<byte[]> scanCursor = sync.scan(cursor, ScanArgs.Builder.limit(1000).match(pattern));
                scanCursor.getKeys().forEach(key -> keys.add((String) getKeySerializer().deserialize(key)));
                finished = scanCursor.isFinished();
                cursor = ScanCursor.of(scanCursor.getCursor());
            } while (!finished);
        } catch (SerializationException e) {
            throw e;
        } catch (Exception e) {
            if (Objects.equals(INVALID_NODE_MES, e.getMessage())) {
                tencentRedis = true;
                return scan(pattern);
            }
            throw new RedisClientException(e.getMessage(), e);
        }
        return keys;
    }


    @Override
    public Long lpush(String key, String... values) {
        try {
            if (Objects.isNull(values) || values.length == 0) {
                return 0L;
            }
            RedisClusterCommands<byte[], byte[]> sync = connection.sync();
            final byte[][] bvalues = new byte[values.length][];
            for (int i = 0; i < values.length; i++) {
                bvalues[i] = getValueSerializer().serialize(values[i]);
            }

            return sync.lpush(getKeySerializer().serialize(key), bvalues);
        } catch (SerializationException e) {
            throw e;
        } catch (Exception e) {
            throw new RedisClientException(e.getMessage(), e);
        }
    }

    @Override
    public Long llen(String key) {
        try {
            RedisClusterCommands<byte[], byte[]> sync = connection.sync();
            return sync.llen(getKeySerializer().serialize(key));
        } catch (SerializationException e) {
            throw e;
        } catch (Exception e) {
            throw new RedisClientException(e.getMessage(), e);
        }
    }

    @Override
    public List<String> lrange(String key, long start, long end) {
        try {
            RedisClusterCommands<byte[], byte[]> sync = connection.sync();
            List<String> list = new ArrayList<>();
            List<byte[]> values = sync.lrange(getKeySerializer().serialize(key), start, end);
            if (CollectionUtils.isEmpty(values)) {
                return list;
            }
            for (byte[] value : values) {
                list.add((String) getValueSerializer().deserialize(value));
            }
            return list;
        } catch (SerializationException e) {
            throw e;
        } catch (Exception e) {
            throw new RedisClientException(e.getMessage(), e);
        }
    }

    @Override
    public Object eval(String script, List<String> keys, List<String> args) {

        try {
            RedisClusterCommands<byte[], byte[]> sync = connection.sync();
            List<byte[]> bkeys = keys.stream().map(key -> getKeySerializer().serialize(key)).collect(Collectors.toList());
            List<byte[]> bargs = args.stream().map(arg -> getValueSerializer().serialize(arg)).collect(Collectors.toList());
            return sync.eval(script, ScriptOutputType.INTEGER, bkeys.toArray(new byte[0][0]), bargs.toArray(new byte[0][0]));
        } catch (SerializationException e) {
            throw e;
        } catch (Exception e) {
            throw new RedisClientException(e.getMessage(), e);
        }
    }

    @Override
    public Long publish(String channel, String message) {

        try {
            RedisPubSubCommands<String, String> sync = pubSubConnection.sync();
            return sync.publish(channel, message);
        } catch (SerializationException e) {
            throw e;
        } catch (Exception e) {
            throw new RedisClientException(e.getMessage(), e);
        }
    }

    @Override
    public void subscribe(RedisMessageListener messageListener, String... channels) {
        try {
            StatefulRedisPubSubConnection<String, String> connection = cluster.connectPubSub();
            logger.info("layering-cache和redis创建订阅关系，订阅频道【{}】", Arrays.toString(channels));
            connection.sync().subscribe(channels);
            connection.addListener(messageListener);
        } catch (SerializationException e) {
            throw e;
        } catch (Exception e) {
            throw new RedisClientException(e.getMessage(), e);
        }
    }

    @Override
    public RedisSerializer<Object> getKeySerializer() {
        return keySerializer;
    }

    @Override
    public RedisSerializer<Object> getValueSerializer() {
        return valueSerializer;
    }

    @Override
    public void setKeySerializer(RedisSerializer keySerializer) {
        this.keySerializer = keySerializer;
    }

    @Override
    public void setValueSerializer(RedisSerializer valueSerializer) {
        this.valueSerializer = valueSerializer;
    }
}