/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under
 * one or more contributor license agreements. See the NOTICE file distributed
 * with this work for additional information regarding copyright ownership.
 * Licensed under the Zeebe Community License 1.0. You may not use this file
 * except in compliance with the Zeebe Community License 1.0.
 */
package io.zeebe.broker.it.client.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.zeebe.broker.it.util.BrokerClassRuleHelper;
import io.zeebe.broker.it.util.GrpcClientRule;
import io.zeebe.broker.test.EmbeddedBrokerRule;
import io.zeebe.client.api.ZeebeFuture;
import io.zeebe.client.api.command.ClientException;
import io.zeebe.model.bpmn.Bpmn;
import io.zeebe.protocol.record.Assertions;
import io.zeebe.protocol.record.Record;
import io.zeebe.protocol.record.intent.WorkflowInstanceIntent;
import io.zeebe.protocol.record.intent.WorkflowInstanceSubscriptionIntent;
import io.zeebe.protocol.record.value.VariableRecordValue;
import io.zeebe.protocol.record.value.WorkflowInstanceRecordValue;
import io.zeebe.test.util.record.RecordingExporter;
import java.time.Duration;
import java.util.Map;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.RuleChain;

public final class MessageCorrelationTest {

  private static final String CORRELATION_KEY_VARIABLE = "orderId";
  private static final String CATCH_EVENT_ELEMENT_ID = "catch-event";
  private static final String MESSAGE_NAME = "order canceled";

  private static final EmbeddedBrokerRule BROKER_RULE = new EmbeddedBrokerRule();
  private static final GrpcClientRule CLIENT_RULE = new GrpcClientRule(BROKER_RULE);

  @ClassRule
  public static RuleChain ruleChain = RuleChain.outerRule(BROKER_RULE).around(CLIENT_RULE);

  @Rule public final BrokerClassRuleHelper helper = new BrokerClassRuleHelper();

  private long workflowKey;
  private String correlationValue;

  @Before
  public void init() {
    correlationValue = helper.getCorrelationValue();

    workflowKey =
        CLIENT_RULE.deployWorkflow(
            Bpmn.createExecutableProcess("process")
                .startEvent()
                .intermediateCatchEvent(CATCH_EVENT_ELEMENT_ID)
                .message(c -> c.name(MESSAGE_NAME).zeebeCorrelationKey(CORRELATION_KEY_VARIABLE))
                .endEvent()
                .done());
  }

  @Test
  public void shouldCorrelateMessage() {
    // given
    final long workflowInstanceKey =
        CLIENT_RULE
            .getClient()
            .newCreateInstanceCommand()
            .workflowKey(workflowKey)
            .variables(Map.of(CORRELATION_KEY_VARIABLE, correlationValue))
            .send()
            .join()
            .getWorkflowInstanceKey();

    // when
    CLIENT_RULE
        .getClient()
        .newPublishMessageCommand()
        .messageName(MESSAGE_NAME)
        .correlationKey(correlationValue)
        .variables(Map.of("foo", "bar"))
        .send()
        .join();

    // then
    final Record<WorkflowInstanceRecordValue> workflowInstanceEvent =
        RecordingExporter.workflowInstanceRecords(WorkflowInstanceIntent.ELEMENT_COMPLETED)
            .withWorkflowInstanceKey(workflowInstanceKey)
            .withElementId(CATCH_EVENT_ELEMENT_ID)
            .getFirst();

    final Record<VariableRecordValue> variableEvent =
        RecordingExporter.variableRecords()
            .withName("foo")
            .withWorkflowInstanceKey(workflowInstanceKey)
            .getFirst();
    Assertions.assertThat(variableEvent.getValue())
        .hasValue("\"bar\"")
        .hasScopeKey(workflowInstanceEvent.getValue().getWorkflowInstanceKey());
  }

  @Test
  public void shouldCorrelateMessageWithZeroTTL() {
    // given
    final long workflowInstanceKey =
        CLIENT_RULE
            .getClient()
            .newCreateInstanceCommand()
            .workflowKey(workflowKey)
            .variables(Map.of(CORRELATION_KEY_VARIABLE, correlationValue))
            .send()
            .join()
            .getWorkflowInstanceKey();

    assertThat(
            RecordingExporter.workflowInstanceSubscriptionRecords(
                    WorkflowInstanceSubscriptionIntent.OPENED)
                .withWorkflowInstanceKey(workflowInstanceKey)
                .withMessageName(MESSAGE_NAME)
                .exists())
        .isTrue();

    // when
    CLIENT_RULE
        .getClient()
        .newPublishMessageCommand()
        .messageName(MESSAGE_NAME)
        .correlationKey(correlationValue)
        .timeToLive(Duration.ZERO)
        .send()
        .join();

    // then
    assertThat(
            RecordingExporter.workflowInstanceRecords(WorkflowInstanceIntent.ELEMENT_COMPLETED)
                .withWorkflowInstanceKey(workflowInstanceKey)
                .withElementId(CATCH_EVENT_ELEMENT_ID)
                .exists())
        .isTrue();
  }

  @Test
  public void shouldRejectMessageWithSameId() {
    // given
    CLIENT_RULE
        .getClient()
        .newPublishMessageCommand()
        .messageName(MESSAGE_NAME)
        .correlationKey(correlationValue)
        .messageId("foo")
        .send()
        .join();

    // when
    final ZeebeFuture<Void> future =
        CLIENT_RULE
            .getClient()
            .newPublishMessageCommand()
            .messageName(MESSAGE_NAME)
            .correlationKey(correlationValue)
            .messageId("foo")
            .send();

    // then
    assertThatThrownBy(future::join)
        .isInstanceOf(ClientException.class)
        .hasMessageContaining(
            "Expected to publish a new message with id 'foo', but a message with that id was already published");
  }
}
