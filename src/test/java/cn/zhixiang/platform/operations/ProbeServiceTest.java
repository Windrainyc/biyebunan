package cn.zhixiang.platform.operations;
import org.junit.jupiter.api.Test;
import org.springframework.boot.health.actuate.endpoint.*;
import org.springframework.boot.health.contributor.Status;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;
class ProbeServiceTest {
 @Test void failedReadinessDoesNotChangeLivenessAndNeverLeaksDetails(){
  var endpoint=mock(HealthEndpoint.class);var up=mock(IndicatedHealthDescriptor.class);var down=mock(IndicatedHealthDescriptor.class);
  when(up.getStatus()).thenReturn(Status.UP);when(down.getStatus()).thenReturn(Status.OUT_OF_SERVICE);
  when(endpoint.healthForPath("liveness")).thenReturn(up);when(endpoint.healthForPath("readiness")).thenReturn(down);
  var controller=new ProbeController(new ProbeService(endpoint));
  assertEquals(200,controller.live().getStatusCode().value());assertEquals(503,controller.ready().getStatusCode().value());assertEquals("DOWN",controller.ready().getBody().status());
  when(endpoint.healthForPath("readiness")).thenThrow(new IllegalStateException("internal-sensitive-error"));assertEquals("DOWN",controller.ready().getBody().status());
  doReturn(null).when(endpoint).healthForPath("readiness");assertEquals(503,controller.ready().getStatusCode().value());
 }
}
