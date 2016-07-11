/*
 * Copyright 2016 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.integration.samples.dsl.kafka;

import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

import org.I0Itec.zkclient.ZkClient;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Bean;
import org.springframework.integration.annotation.Gateway;
import org.springframework.integration.annotation.IntegrationComponentScan;
import org.springframework.integration.annotation.MessagingGateway;
import org.springframework.integration.dsl.IntegrationFlow;
import org.springframework.integration.dsl.IntegrationFlows;
import org.springframework.integration.dsl.kafka.Kafka09;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.support.TopicPartitionInitialOffset;
import org.springframework.messaging.Message;
import org.springframework.stereotype.Service;

import kafka.admin.AdminUtils;
import kafka.common.TopicExistsException;
import kafka.utils.ZKStringSerializer$;
import kafka.utils.ZkUtils;

/**
 * @author Gary Russell
 * @author Artem Bilan
 * @since 4.3
 */
@SpringBootApplication
@IntegrationComponentScan
public class Application {

	@Value("${kafka.topic}")
	private String topic;

	@Value("${kafka.messageKey}")
	private String messageKey;

	@Value("${kafka.broker.address}")
	private String brokerAddress;

	public static void main(String[] args) throws Exception {
		ConfigurableApplicationContext context
				= new SpringApplicationBuilder(Application.class)
				.web(false)
				.run(args);
		KafkaGateway kafkaGateway = context.getBean(KafkaGateway.class);
		for (int i = 0; i < 10; i++) {
			String message = "foo" + i;
			System.out.println("Send to Kafka: " + message);
			kafkaGateway.sendToKafka(message);
		}

		Message<?> received = kafkaGateway.receiveFromKafka();
		while (received != null) {
			System.out.println(received);
			received = kafkaGateway.receiveFromKafka();
		}

		context.close();
		System.exit(0);
	}

	@MessagingGateway
	public interface KafkaGateway {

		@Gateway(requestChannel = "toKafka.input")
		void sendToKafka(String payload);

		@Gateway(replyChannel = "fromKafka", replyTimeout = 10000)
		Message<?> receiveFromKafka();

	}


	@Bean
	public ProducerFactory<String, String> producerFactory() {
		Map<String, Object> props = new HashMap<>();
		props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, this.brokerAddress);
		props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
		props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
		return new DefaultKafkaProducerFactory<>(props);
	}

	@Bean
	public IntegrationFlow toKafka() {
		return f -> f
				.handle(Kafka09.outboundChannelAdapter(producerFactory())
						.topic(this.topic)
						.messageKey(this.messageKey));
	}

	@Bean
	public ConsumerFactory<String, String> consumerFactory() {
		Map<String, Object> props = new HashMap<>();
		props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, this.brokerAddress);
		props.put(ConsumerConfig.GROUP_ID_CONFIG, "siTestGroup");
		props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, true);
		props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
		props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
		return new DefaultKafkaConsumerFactory<>(props);
	}

	@Bean
	public IntegrationFlow fromKafka() {
		return IntegrationFlows
				.from(Kafka09.messageDriverChannelAdapter(consumerFactory(),
						new TopicPartitionInitialOffset(this.topic, 0)))
				.channel(c -> c.queue("fromKafka"))
				.get();
	}

	@Service
	public static class TopicCreator implements SmartLifecycle {

		@Value("${kafka.topic}")
		private String topic;

		@Value("${kafka.zookeeper.connect}")
		private String zookeeperConnect;

		private volatile boolean running;

		@Override
		public void start() {
			ZkUtils zkUtils = new ZkUtils(new ZkClient(this.zookeeperConnect, 6000, 6000,
					ZKStringSerializer$.MODULE$), null, false);
			try {
				AdminUtils.createTopic(zkUtils, topic, 1, 1, new Properties());
			}
			catch (TopicExistsException e) {
				// no-op
			}
			this.running = true;
		}

		@Override
		public void stop() {
		}

		@Override
		public boolean isRunning() {
			return this.running;
		}

		@Override
		public int getPhase() {
			return Integer.MIN_VALUE;
		}

		@Override
		public boolean isAutoStartup() {
			return true;
		}

		@Override
		public void stop(Runnable callback) {
			callback.run();
		}

	}

}
