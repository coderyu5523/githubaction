package com.example.githubaction;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Bean;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;

@Configuration
public class RabbitConfig {
  private final AppProps props;
  RabbitConfig(AppProps props){ this.props = props; }

  @Bean Queue fileQueue() {
    return QueueBuilder.durable(props.queue()).build();
  }
}
