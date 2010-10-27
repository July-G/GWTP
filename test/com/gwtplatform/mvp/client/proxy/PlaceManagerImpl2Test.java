/**
 * Copyright 2010 ArcBees Inc.
 * 
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package com.gwtplatform.mvp.client.proxy;

import com.google.gwt.junit.GWTMockUtilities;
import com.google.gwt.user.client.Command;
import com.google.inject.Inject;

import com.gwtplatform.mvp.client.EventBus;
import com.gwtplatform.tester.DeferredCommandManager;
import com.gwtplatform.tester.mockito.AutomockingModule;
import com.gwtplatform.tester.mockito.GuiceMockitoJUnitRunner;
import com.gwtplatform.tester.mockito.TestScope;

import static org.junit.Assert.assertEquals;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.eq;
import static org.mockito.Matchers.isA;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;

/**
 * Unit tests for {@link PlaceManagerImpl}.
 * 
 * @author Philippe Beaudoin
 */
@RunWith(GuiceMockitoJUnitRunner.class)
public class PlaceManagerImpl2Test {

  /**
   * Guice test module.
   */
  public static class Module extends AutomockingModule {
    @Override
    protected void configureTest() {
      GWTMockUtilities.disarm();
      bind(DeferredCommandManager.class).in(TestScope.SINGLETON);
      bind(PlaceManager.class).to(TestPlaceManager.class).in(TestScope.SINGLETON);
    }
  }

  // SUT
  @Inject PlaceManager placeManager;
  
  @Inject DeferredCommandManager deferredCommandManager;
  @Inject EventBus eventBus;
  @Inject PlaceManagerWindowMethods gwtWindowMethods;
  
  @Test
  public void placeManagerUserCallUpdateHistoryWhenRevealingPlace() {
    // Given
    doAnswer(new Answer<Void>() {
      @Override
      public Void answer(InvocationOnMock invocation) throws Throwable {
        Object[] args = invocation.getArguments();
        deferredCommandManager.addCommand(new Command() {
          @Override
          public void execute() {
            placeManager.updateHistory(new PlaceRequest("dummyNameToken").with("dummyParam", "dummyValue"));
          } });
        ((PlaceRequestInternalEvent) args[1]).setHandled();
        return null;
      }
    }).when(eventBus).fireEvent(eq(placeManager), isA(PlaceRequestInternalEvent.class));
    
    // When
    placeManager.revealPlace(new PlaceRequest("dummyNameToken"));
    deferredCommandManager.pump();
    
    // Then
    PlaceRequest placeRequest = placeManager.getCurrentPlaceRequest();
    assertEquals("dummyNameToken", placeRequest.getNameToken());
    assertEquals(1, placeRequest.getParameterNames().size());
    assertEquals("dummyValue", placeRequest.getParameter("dummyParam", null));
    
    verify(gwtWindowMethods).setBrowserHistoryToken(any(String.class), eq(false));
  }
}