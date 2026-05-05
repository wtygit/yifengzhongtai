package com.jeecg.modules.jmreport.satoken.config;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.jsontype.impl.LaissezFaireSubTypeValidator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.Jackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

/**
 * RedisTemplate实例化
 */
@Slf4j
@Configuration
@ConditionalOnMissingBean(RedisTemplate.class)
public class RedisConfig {

    @ConditionalOnBean(RedisConnectionFactory.class)
    @ConditionalOnMissingBean(RedisTemplate.class)
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory connectionFactory) {
        log.info(" --- JmDrag redis config init (主redisTemplate不存在，创建备用RedisTemplate) --- ");
        Jackson2JsonRedisSerializer<Object> jackson2JsonRedisSerializer = jacksonSerializer();
        RedisTemplate<String, Object> redisTemplate = new RedisTemplate<>();
        redisTemplate.setConnectionFactory(connectionFactory);
        RedisSerializer<String> stringSerializer = new StringRedisSerializer();

        // key序列化
        redisTemplate.setKeySerializer(stringSerializer);
        // value序列化
        redisTemplate.setValueSerializer(jackson2JsonRedisSerializer);
        // Hash key序列化
        redisTemplate.setHashKeySerializer(stringSerializer);
        // Hash value序列化
        redisTemplate.setHashValueSerializer(jackson2JsonRedisSerializer);
        redisTemplate.afterPropertiesSet();
        return redisTemplate;
    }

    private Jackson2JsonRedisSerializer<Object> jacksonSerializer() {
        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.setVisibility(PropertyAccessor.ALL, JsonAutoDetect.Visibility.ANY);
        objectMapper.activateDefaultTyping(LaissezFaireSubTypeValidator.instance, ObjectMapper.DefaultTyping.NON_FINAL);
        // 直接在构造器中传入 objectMapper
        return new Jackson2JsonRedisSerializer<>(objectMapper, Object.class);
    }
}
