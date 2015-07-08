package br.com.rabbit.storm.spouts;

import java.io.IOException;
import java.net.Socket;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import backtype.storm.spout.SpoutOutputCollector;
import backtype.storm.task.TopologyContext;
import backtype.storm.topology.IRichSpout;
import backtype.storm.topology.OutputFieldsDeclarer;
import backtype.storm.tuple.Fields;
import backtype.storm.tuple.Values;
import backtype.storm.utils.Utils;

import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import com.rabbitmq.client.ConsumerCancelledException;
import com.rabbitmq.client.QueueingConsumer;
import com.rabbitmq.client.ShutdownSignalException;

public class AMQPRecvSpoutSSL implements IRichSpout {
	
	/**
	 * 
	 */
	private static final long serialVersionUID = 1L;

	/**
	 * Logger instance
	 */
    private static final Logger log = LoggerFactory.getLogger(AMQPRecvSpoutSSL.class);

	private static final long CONFIG_PREFETCH_COUNT = 0;
	private static final long DEFAULT_PREFETCH_COUNT = 0;
	private static final long WAIT_AFTER_SHUTDOWN_SIGNAL = 0;
	private static final long WAIT_FOR_NEXT_MESSAGE = 1L;

	private static final String EXCHANGE_NAME = "topic_logs";
	//private static final String QUEUE_NAME = "Grupo1";


	private String amqpHost;
	private int amqpPort;
	private String amqpUsername;
	private String amqpPasswd;
	private String amqpVhost;
	private boolean requeueOnFail;
	private boolean autoAck;

	private int prefetchCount;
	

	private SpoutOutputCollector collector;

	private Connection amqpConnection;
	private Channel amqpChannel;
	private QueueingConsumer amqpConsumer;
	private String amqpConsumerTag;
	private boolean spoutActive;

	// The constructor where we set initialize all properties
	public AMQPRecvSpoutSSL(String host, int port, String username,
			String password, String vhost, boolean requeueOnFail,
			boolean autoAck) {
		this.amqpHost = host;
		this.amqpPort = port;
		this.amqpUsername = username;
		this.amqpPasswd = password;
		this.amqpVhost = vhost;
		this.requeueOnFail = requeueOnFail;
		this.autoAck = autoAck;
	}

	/*
	 * Open method of the spout , here we initialize the prefetch count , this
	 * parameter specified how many messages would be prefetched from the queue
	 * by the spout – to increase the efficiency of the solution
	 */
	public void open(@SuppressWarnings("rawtypes") Map conf,
			TopologyContext context, SpoutOutputCollector collector) {
		Long prefetchCount = (Long) conf.get(CONFIG_PREFETCH_COUNT);
		if (prefetchCount == null) {
			log.info("Using default prefetch-count");
			prefetchCount = DEFAULT_PREFETCH_COUNT;
		} else if (prefetchCount < 1) {
			throw new IllegalArgumentException(CONFIG_PREFETCH_COUNT
					+ " must be at least 1");
		}
		this.prefetchCount = prefetchCount.intValue();

		try {
			this.collector = collector;

			setupAMQP();
		} catch (IOException e) {
			log.error("AMQP setup failed", e);
			log.warn("AMQP setup failed, will attempt to reconnect...");
			Utils.sleep(WAIT_AFTER_SHUTDOWN_SIGNAL);
			reconnect();
		}

	}

	/**
	 * Reconnect to an AMQP broker.in case the connection breaks at some point
	 */
	private void reconnect() {
		log.info("Reconnecting to AMQP broker...");
		try {
			setupAMQP();
		} catch (IOException e) {
			log.warn("Failed to reconnect to AMQP broker", e);
		}
	}

	/**
	 * Setup a connection with an AMQP broker.
	 * 
	 * @throws IOException
	 *             This is the method where we actually connect to the queue
	 *             using AMQP client api’s
	 */
	private void setupAMQP() throws IOException {
		final int prefetchCount = this.prefetchCount;

		final ConnectionFactory connectionFactory = new ConnectionFactory();

		connectionFactory.setHost(amqpHost);
		connectionFactory.setPort(amqpPort);
		connectionFactory.setUsername(amqpUsername);
		connectionFactory.setPassword(amqpPasswd);
		connectionFactory.setVirtualHost(amqpVhost);

		amqpConnection = connectionFactory.newConnection();
		amqpChannel = amqpConnection.createChannel();
		String queueName = this.amqpChannel.queueDeclare().getQueue();

		log.info("Setting basic.qos prefetch-count to " + prefetchCount);
		amqpChannel.basicQos(prefetchCount);

		amqpChannel.exchangeDeclare(EXCHANGE_NAME, "topic");
		
		//String[] bindingKeys = new String[] {"bittorrent","dchp", "http", "ssdp", "ssh", "ssl", "unknown"};
		String[] bindingKeys = new String[] {"ssl"};
        for(String bindingKey : bindingKeys){
        	amqpChannel.queueBind(queueName, EXCHANGE_NAME, bindingKey);
        }
        
		amqpConsumer = new QueueingConsumer(amqpChannel);
		amqpChannel.basicConsume(queueName,
				true, amqpConsumer);
		

	}

	/*
	 * Cancels the queue subscription, and disconnects from the AMQP broker.
	 */
	public void close() {
		try {
			if (amqpChannel != null) {
				if (amqpConsumerTag != null) {
					amqpChannel.basicCancel(amqpConsumerTag);
				}

				amqpChannel.close();
			}
		} catch (IOException e) {
			log.warn("Error closing AMQP channel", e);
		}

		try {
			if (amqpConnection != null) {
				amqpConnection.close();
			}
		} catch (IOException e) {
			log.warn("Error closing AMQP connection", e);
		}

	}

	/*
	 * Emit message received from queue into collector
	 */
	public void nextTuple() {
		while (true) {
            QueueingConsumer.Delivery delivery;
			try {
				delivery = amqpConsumer.nextDelivery();
				String message = new String(delivery.getBody());
	            String routingKey = delivery.getEnvelope().getRoutingKey();
	            collector.emit(new Values(message), routingKey);
	            //System.out.println(message);
			} catch (ShutdownSignalException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			} catch (ConsumerCancelledException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			} catch (InterruptedException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
				
			}
		}
	

	/*
	 * ack method to acknowledge the message that is successfully processed
	 */

	public void ack(Object msgId) {
		if (msgId instanceof Long) {
			final long deliveryTag = (Long) msgId;
			if (amqpChannel != null) {
				try {
					amqpChannel.basicAck(deliveryTag, false);
				} catch (IOException e) {
					log.warn("Failed to ack delivery-tag " + deliveryTag, e);
				} catch (ShutdownSignalException e) {
					log.warn(
							"AMQP connection failed. Failed to ack delivery-tag "
									+ deliveryTag, e);
				}
			}
		} else {
			log.warn(String.format("don't know how to ack(%s: %s)", msgId
					.getClass().getName(), msgId));
		}
	}

	public void fail(Object msgId) {
		if (msgId instanceof Long) {
			final long deliveryTag = (Long) msgId;
			if (amqpChannel != null) {
				try {
					if (amqpChannel.isOpen()) {
						if (!this.autoAck) {
							amqpChannel.basicReject(deliveryTag, requeueOnFail);
						}
					} else {
						reconnect();
					}
				} catch (IOException e) {
					log.warn("Failed to reject delivery-tag " + deliveryTag, e);
				}
			}
		} else {
			log.warn(String.format("don't know how to reject(%s: %s)", msgId
					.getClass().getName(), msgId));
		}
	}

	public void declareOutputFields(OutputFieldsDeclarer declarer) {
		declarer.declare(new Fields("messages"));

	}

	public void activate() {
		// TODO Auto-generated method stub
		
	}

	public void deactivate() {
		// TODO Auto-generated method stub
		
	}

	public Map<String, Object> getComponentConfiguration() {
		// TODO Auto-generated method stub
		return null;
	}

}