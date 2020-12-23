/*
 * Copyright 2020 The gRPC Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.grpc;

import static com.google.common.truth.Truth.assertThat;
import static org.mockito.Mockito.mock;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests for {@link ChoiceChannelCredentials}. */
@RunWith(JUnit4.class)
public class ChoiceChannelCredentialsTest {
  @Test
  public void withoutBearTokenGivesChoiceOfCredsWithoutToken() {
    final ChannelCredentials creds1WithoutToken = mock(ChannelCredentials.class);
    ChannelCredentials creds1 = new ChannelCredentials() {
      @Override
      public ChannelCredentials withoutBearerTokens() {
        return creds1WithoutToken;
      }
    };
    final ChannelCredentials creds2WithoutToken = mock(ChannelCredentials.class);
    ChannelCredentials creds2 = new ChannelCredentials() {
      @Override
      public ChannelCredentials withoutBearerTokens() {
        return creds2WithoutToken;
      }
    };
    ChannelCredentials choice = ChoiceChannelCredentials.create(creds1, creds2);
    ChannelCredentials choiceWithouToken = choice.withoutBearerTokens();
    assertThat(choiceWithouToken).isInstanceOf(ChoiceChannelCredentials.class);
    assertThat(((ChoiceChannelCredentials) choiceWithouToken).getCredentialsList())
        .containsExactly(creds1WithoutToken, creds2WithoutToken);
  }
}
