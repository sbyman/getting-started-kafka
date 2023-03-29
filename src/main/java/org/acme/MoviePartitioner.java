package org.acme;

import java.util.Map;

import org.apache.kafka.clients.producer.Partitioner;
import org.apache.kafka.common.Cluster;


public class MoviePartitioner implements Partitioner {

    @Override
    public int partition(String topic, Object key, byte[] keyBytes, Object value, byte[] valueBytes, Cluster cluster) {

        Integer partitions = cluster.partitionCountForTopic(topic);
        if(key != null && key instanceof Long){
            Long id = (Long) key;
            return Math.abs( id.hashCode() ) % partitions;
        } else {
            return 0;
		}
    }

    @Override
    public void configure(Map<String, ?> configs) {
		
    }
	
    @Override
    public void close() {

    }

}
