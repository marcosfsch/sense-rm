/*
 * SENSE Resource Manager (SENSE-RM) Copyright (c) 2016, The Regents
 * of the University of California, through Lawrence Berkeley National
 * Laboratory (subject to receipt of any required approvals from the
 * U.S. Dept. of Energy).  All rights reserved.
 *
 * If you have questions about your rights to use or distribute this
 * software, please contact Berkeley Lab's Innovation & Partnerships
 * Office at IPO@lbl.gov.
 *
 * NOTICE.  This Software was developed under funding from the
 * U.S. Department of Energy and the U.S. Government consequently retains
 * certain rights. As such, the U.S. Government has been granted for
 * itself and others acting on its behalf a paid-up, nonexclusive,
 * irrevocable, worldwide license in the Software to reproduce,
 * distribute copies to the public, prepare derivative works, and perform
 * publicly and display publicly, and to permit other to do so.
 *
 */
package net.es.sense.rm.driver.nsi.cs.api;

import javax.jws.WebService;
import javax.xml.bind.JAXBElement;
import javax.xml.bind.JAXBException;
import javax.xml.datatype.XMLGregorianCalendar;
import javax.xml.ws.Holder;
import lombok.extern.slf4j.Slf4j;
import net.es.sense.rm.driver.nsi.cs.db.Operation;
import net.es.sense.rm.driver.nsi.cs.db.OperationMapRepository;
import net.es.sense.rm.driver.nsi.cs.db.Reservation;
import net.es.sense.rm.driver.nsi.cs.db.ReservationService;
import net.es.sense.rm.driver.nsi.cs.db.StateType;
import org.ogf.schemas.nsi._2013._12.connection.requester.ServiceException;
import org.ogf.schemas.nsi._2013._12.connection.types.DataPlaneStateChangeRequestType;
import org.ogf.schemas.nsi._2013._12.connection.types.DataPlaneStatusType;
import org.ogf.schemas.nsi._2013._12.connection.types.ErrorEventType;
import org.ogf.schemas.nsi._2013._12.connection.types.GenericAcknowledgmentType;
import org.ogf.schemas.nsi._2013._12.connection.types.GenericConfirmedType;
import org.ogf.schemas.nsi._2013._12.connection.types.GenericErrorType;
import org.ogf.schemas.nsi._2013._12.connection.types.GenericFailedType;
import org.ogf.schemas.nsi._2013._12.connection.types.LifecycleStateEnumType;
import org.ogf.schemas.nsi._2013._12.connection.types.MessageDeliveryTimeoutRequestType;
import org.ogf.schemas.nsi._2013._12.connection.types.ObjectFactory;
import org.ogf.schemas.nsi._2013._12.connection.types.ProvisionStateEnumType;
import org.ogf.schemas.nsi._2013._12.connection.types.QueryNotificationConfirmedType;
import org.ogf.schemas.nsi._2013._12.connection.types.QueryRecursiveConfirmedType;
import org.ogf.schemas.nsi._2013._12.connection.types.QueryResultConfirmedType;
import org.ogf.schemas.nsi._2013._12.connection.types.QuerySummaryConfirmedType;
import org.ogf.schemas.nsi._2013._12.connection.types.ReservationConfirmCriteriaType;
import org.ogf.schemas.nsi._2013._12.connection.types.ReservationStateEnumType;
import org.ogf.schemas.nsi._2013._12.connection.types.ReserveConfirmedType;
import org.ogf.schemas.nsi._2013._12.connection.types.ReserveTimeoutRequestType;
import org.ogf.schemas.nsi._2013._12.framework.headers.CommonHeaderType;
import org.ogf.schemas.nsi._2013._12.framework.types.ServiceExceptionType;
import org.springframework.stereotype.Component;

/**
 * This is the NSI CS 2.1 web service requester endpoint used to receive responses from our associated uPA.
 * Communication between the requester thread and this requester response endpoint is controlled using a semaphore
 * allowing the request thread to block on the returned response. Reservation state us updated through the
 * ReservationService which maintains reservations in the database.
 *
 * @author hacksaw
 */
@Slf4j
@Component
@WebService(
        serviceName = "ConnectionServiceRequester",
        portName = "ConnectionServiceRequesterPort",
        endpointInterface = "org.ogf.schemas.nsi._2013._12.connection.requester.ConnectionRequesterPort",
        targetNamespace = "http://schemas.ogf.org/nsi/2013/12/connection/requester",
        wsdlLocation = "")
public class ConnectionService {

  // We store reservations using the reservation service.
  private final ReservationService reservationService;

  // We synchronize with the requester thread using the operationMap that holds a semaphore.
  private final OperationMapRepository operationMap;

  // Our NSI CS object factory for creating protocol objects.
  private final static ObjectFactory FACTORY = new ObjectFactory();

  /**
   * We initialize the ConnectionService component with the needed references since this component does not support
   * autowiring.
   *
   * @param reservationService We store reservations using the reservation service.
   * @param operationMap We synchronize with the requester thread using the operationMap that holds a semaphore.
   */
  public ConnectionService(ReservationService reservationService, OperationMapRepository operationMap) {
    this.reservationService = reservationService;
    this.operationMap = operationMap;
  }

  /**
   * Endpoint receiving the NSI CS reserveConfirmed response message.
   *
   * @param reserveConfirmed
   * @param header
   * @return
   * @throws ServiceException
   */
  public GenericAcknowledgmentType reserveConfirmed(
          ReserveConfirmedType reserveConfirmed, Holder<CommonHeaderType> header) throws ServiceException {
    CommonHeaderType value = header.value;
    log.info("[ConnectionService] reserveConfirmed recieved for correlationId = {}, connectionId: {}",
            value.getCorrelationId(), reserveConfirmed.getConnectionId());

    ReservationConfirmCriteriaType criteria = reserveConfirmed.getCriteria();
    DataPlaneStatusType dataPlaneStatus = FACTORY.createDataPlaneStatusType();
    dataPlaneStatus.setVersion(criteria.getVersion());
    dataPlaneStatus.setActive(false);
    dataPlaneStatus.setVersionConsistent(true);

    Reservation reservation = processConfirmedCriteria(
            header.value.getProviderNSA(),
            reserveConfirmed.getGlobalReservationId(),
            reserveConfirmed.getDescription(),
            reserveConfirmed.getConnectionId(),
            ReservationStateEnumType.RESERVE_HELD,
            ProvisionStateEnumType.RELEASED,
            LifecycleStateEnumType.CREATED,
            dataPlaneStatus,
            criteria);

    if (reservation != null) {
      Reservation r = reservationService.get(reservation.getProviderNsa(), reservation.getConnectionId());
      if (r == null) {
        // We have not seen this reservation before so store it.
        log.info("[ConnectionService] reserveConfirmed: storing new reservation, cid = {}",
                reservation.getConnectionId());
        reservationService.store(reservation);
      } else if (r.diff(reservation)) {
        // We have to determine if the stored reservation needs to be updated.
        log.info("[ConnectionService] reserveConfirmed: storing reservation update, cid = {}",
                reservation.getConnectionId());
        reservation.setId(r.getId());
        reservationService.store(reservation);
      } else {
        log.info("[ConnectionService] reserveConfirmed: reservation no change, cid = {}",
                reservation.getConnectionId());
      }
    }

    Operation op = operationMap.get(value.getCorrelationId());
    if (op == null) {
      log.error("[ConnectionService] reserveConfirmed can't find outstanding operation for correlationId = {}",
              value.getCorrelationId());
    } else {
      op.setState(StateType.reserved);
      op.getCompleted().release();
    }

    return FACTORY.createGenericAcknowledgmentType();
  }

  private Reservation processConfirmedCriteria(
          String providerNsa,
          String gid,
          String description,
          String cid,
          ReservationStateEnumType reservationState,
          ProvisionStateEnumType provisionState,
          LifecycleStateEnumType lifecycleState,
          DataPlaneStatusType dataPlaneStatus,
          ReservationConfirmCriteriaType criteria) {

    log.info("[ConnectionService] processConfirmedCriteria: connectionId = {}", cid);

    Reservation reservation = new Reservation();
    reservation.setGlobalReservationId(gid);
    reservation.setDescription(description);
    reservation.setDiscovered(System.currentTimeMillis());
    reservation.setProviderNsa(providerNsa);
    reservation.setConnectionId(cid);
    reservation.setReservationState(reservationState);
    reservation.setProvisionState(provisionState);
    reservation.setLifecycleState(lifecycleState);
    reservation.setDataPlaneActive(dataPlaneStatus.isActive());
    reservation.setVersion(criteria.getVersion());
    reservation.setServiceType(criteria.getServiceType());
    reservation.setStartTime(getStartTime(criteria.getSchedule().getStartTime()));
    reservation.setEndTime(getEndTime(criteria.getSchedule().getEndTime()));

    // Now we need to determine the network based on the STP used in the service.
    try {
      CsUtils.serializeP2PS(criteria.getServiceType(), criteria.getAny(), reservation);
      return reservation;
    } catch (JAXBException ex) {
      log.error("[ConnectionService] processReservation failed for connectionId = {}",
              reservation.getConnectionId(), ex);
      return null;
    }
  }

  public GenericAcknowledgmentType reserveFailed(GenericFailedType reserveFailed, Holder<CommonHeaderType> header) throws ServiceException {
    CommonHeaderType value = header.value;
    log.info("[ConnectionService] reserveFailed recieved for correlationId = {}, connectionId: {}",
            value.getCorrelationId(), reserveFailed.getConnectionId());

    // First we update the corresponding reservation in the datbase.
    Reservation r = reservationService.get(value.getProviderNSA(), reserveFailed.getConnectionId());
    if (r == null) {
      // We have not seen this reservation before so ignore it.
      log.info("[ConnectionService] reserveFailed: no reference to reservation, cid = {}",
              reserveFailed.getConnectionId());
    } else {
      // We have to determine if the stored reservation needs to be updated.
      log.info("[ConnectionService] reserveFailed: storing reservation update, cid = {}",
              reserveFailed.getConnectionId());
      r.setReservationState(ReservationStateEnumType.RESERVE_FAILED);
      r.setDiscovered(System.currentTimeMillis());
      reservationService.store(r);
    }

    Operation op = operationMap.get(value.getCorrelationId());
    if (op == null) {
      log.error("[ConnectionService] reserveFailed can't find outstanding operation for correlationId = {}",
              value.getCorrelationId());
    } else {
      op.setState(StateType.failed);
      op.setException(reserveFailed.getServiceException());
      op.getCompleted().release();
    }

    return FACTORY.createGenericAcknowledgmentType();
  }

  public GenericAcknowledgmentType reserveCommitConfirmed(GenericConfirmedType reserveCommitConfirmed, Holder<CommonHeaderType> header) throws ServiceException {
    CommonHeaderType value = header.value;
    log.info("[ConnectionService] reserveCommitConfirmed recieved for correlationId = {}, connectionId: {}",
            value.getCorrelationId(), reserveCommitConfirmed.getConnectionId());

    // First we update the corresponding reservation in the datbase.
    Reservation r = reservationService.get(value.getProviderNSA(), reserveCommitConfirmed.getConnectionId());
    if (r == null) {
      // We have not seen this reservation before so ignore it.
      log.info("[ConnectionService] reserveCommitConfirmed: no reference to reservation, cid = {}",
              reserveCommitConfirmed.getConnectionId());
    } else {
      // We have to determine if the stored reservation needs to be updated.
      log.info("[ConnectionService] reserveCommitConfirmed: storing reservation update, cid = {}",
              reserveCommitConfirmed.getConnectionId());
      r.setReservationState(ReservationStateEnumType.RESERVE_START);
      r.setDiscovered(System.currentTimeMillis());
      reservationService.store(r);
    }

    Operation op = operationMap.get(value.getCorrelationId());
    if (op == null) {
      log.error("[ConnectionService] reserveCommitConfirmed can't find outstanding operation for correlationId = {}",
              value.getCorrelationId());
    } else {
      op.setState(StateType.committed);
      op.getCompleted().release();
    }

    return FACTORY.createGenericAcknowledgmentType();
  }

  public GenericAcknowledgmentType reserveCommitFailed(GenericFailedType reserveCommitFailed, Holder<CommonHeaderType> header) throws ServiceException {
    CommonHeaderType value = header.value;
    log.info("[ConnectionService] reserveCommitFailed recieved for correlationId = {}, connectionId: {}",
            value.getCorrelationId(), reserveCommitFailed.getConnectionId());

    Reservation r = reservationService.get(value.getProviderNSA(), reserveCommitFailed.getConnectionId());
    if (r == null) {
      // We have not seen this reservation before so ignore it.
      log.info("[ConnectionService] reserveCommitFailed: no reference to reservation, cid = {}",
              reserveCommitFailed.getConnectionId());
    } else {
      // We have to determine if the stored reservation needs to be updated.
      log.info("[ConnectionService] reserveCommitFailed: storing reservation update, cid = {}",
              reserveCommitFailed.getConnectionId());
      r.setReservationState(ReservationStateEnumType.RESERVE_FAILED);
      r.setDiscovered(System.currentTimeMillis());
      reservationService.store(r);
    }

    Operation op = operationMap.get(value.getCorrelationId());
    if (op == null) {
      log.error("[ConnectionService] reserveCommitFailed can't find outstanding operation for correlationId = {}",
              value.getCorrelationId());
    } else {
      op.setState(StateType.failed);
      op.setException(reserveCommitFailed.getServiceException());
      op.getCompleted().release();
    }

    return FACTORY.createGenericAcknowledgmentType();
  }

  public GenericAcknowledgmentType reserveAbortConfirmed(GenericConfirmedType reserveAbortConfirmed, Holder<CommonHeaderType> header) throws ServiceException {
    CommonHeaderType value = header.value;
    log.info("[ConnectionService] reserveAbortConfirmed recieved for correlationId = {}, connectionId: {}",
            value.getCorrelationId(), reserveAbortConfirmed.getConnectionId());

    Reservation r = reservationService.get(value.getProviderNSA(), reserveAbortConfirmed.getConnectionId());
    if (r == null) {
      // We have not seen this reservation before so ignore it.
      log.info("[ConnectionService] reserveAbortConfirmed: no reference to reservation, cid = {}",
              reserveAbortConfirmed.getConnectionId());
    } else {
      // We have to determine if the stored reservation needs to be updated.
      log.info("[ConnectionService] reserveAbortConfirmed: storing reservation update, cid = {}",
              reserveAbortConfirmed.getConnectionId());
      r.setReservationState(ReservationStateEnumType.RESERVE_START);
      r.setDiscovered(System.currentTimeMillis());
      reservationService.store(r);
    }

    Operation op = operationMap.get(value.getCorrelationId());
    if (op == null) {
      log.error("[ConnectionService] reserveAbortConfirmed can't find outstanding operation for correlationId = {}",
              value.getCorrelationId());
    } else {
      op.setState(StateType.aborted);
      op.getCompleted().release();
    }

    return FACTORY.createGenericAcknowledgmentType();
  }

  public GenericAcknowledgmentType provisionConfirmed(GenericConfirmedType provisionConfirmed, Holder<CommonHeaderType> header) throws ServiceException {
    CommonHeaderType value = header.value;
    log.info("[ConnectionService] provisionConfirmed recieved for correlationId = {}, connectionId: {}",
            value.getCorrelationId(), provisionConfirmed.getConnectionId());

    // First we update the corresponding reservation in the datbase.
    Reservation r = reservationService.get(value.getProviderNSA(), provisionConfirmed.getConnectionId());
    if (r == null) {
      // We have not seen this reservation before so ignore it.
      log.info("[ConnectionService] provisionConfirmed: no reference to reservation, cid = {}",
              provisionConfirmed.getConnectionId());
    } else {
      // We have to determine if the stored reservation needs to be updated.
      log.info("[ConnectionService] provisionConfirmed: storing reservation update, cid = {}",
              provisionConfirmed.getConnectionId());
      r.setProvisionState(ProvisionStateEnumType.PROVISIONED);
      r.setDiscovered(System.currentTimeMillis());
      reservationService.store(r);
    }

    Operation op = operationMap.get(value.getCorrelationId());
    if (op == null) {
      log.error("[ConnectionService] provisionConfirmed can't find outstanding operation for correlationId = {}",
              value.getCorrelationId());
    } else {
      op.setState(StateType.provisioned);
      op.getCompleted().release();
    }

    return FACTORY.createGenericAcknowledgmentType();
  }

  public GenericAcknowledgmentType releaseConfirmed(GenericConfirmedType releaseConfirmed, Holder<CommonHeaderType> header) throws ServiceException {
    CommonHeaderType value = header.value;
    log.info("[ConnectionService] releaseConfirmed received for correlationId = {}, connectionId: {}",
            value.getCorrelationId(), releaseConfirmed.getConnectionId());

    // First we update the corresponding reservation in the datbase.
    Reservation r = reservationService.get(header.value.getProviderNSA(), releaseConfirmed.getConnectionId());
    if (r == null) {
      // We have not seen this reservation before so ignore it.
      log.info("[ConnectionService] releaseConfirmed: no reference to reservation, cid = {}",
              releaseConfirmed.getConnectionId());
    } else {
      // We have to determine if the stored reservation needs to be updated.
      log.info("[ConnectionService] releaseConfirmed: storing reservation update, cid = {}",
              releaseConfirmed.getConnectionId());
      r.setProvisionState(ProvisionStateEnumType.RELEASED);
      r.setDiscovered(System.currentTimeMillis());
      reservationService.store(r);
    }

    Operation op = operationMap.get(value.getCorrelationId());
    if (op == null) {
      log.error("[ConnectionService] releaseConfirmed can't find outstanding operation for correlationId = {}",
              value.getCorrelationId());
    } else {
      op.setState(StateType.released);
      op.getCompleted().release();
    }

    return FACTORY.createGenericAcknowledgmentType();
  }

  public GenericAcknowledgmentType terminateConfirmed(GenericConfirmedType terminateConfirmed, Holder<CommonHeaderType> header) throws ServiceException {
    CommonHeaderType value = header.value;
    log.info("[ConnectionService] terminateConfirmed received for correlationId = {}, connectionId: {}",
            value.getCorrelationId(), terminateConfirmed.getConnectionId());

    // First we update the corresponding reservation in the datbase.
    Reservation r = reservationService.get(header.value.getProviderNSA(), terminateConfirmed.getConnectionId());
    if (r == null) {
      // We have not seen this reservation before so ignore it.
      log.info("[ConnectionService] terminateConfirmed: no reference to reservation, cid = {}",
              terminateConfirmed.getConnectionId());
    } else {
      // We have to determine if the stored reservation needs to be updated.
      log.info("[ConnectionService] terminateConfirmed: storing reservation update, cid = {}",
              terminateConfirmed.getConnectionId());
      r.setLifecycleState(LifecycleStateEnumType.TERMINATED);
      r.setDiscovered(System.currentTimeMillis());
      reservationService.store(r);
    }

    Operation op = operationMap.get(value.getCorrelationId());
    if (op == null) {
      log.error("[ConnectionService] terminateConfirmed can't find outstanding operation for correlationId = {}",
              value.getCorrelationId());
    } else {
      op.setState(StateType.terminated);
      op.getCompleted().release();
    }

    return FACTORY.createGenericAcknowledgmentType();
  }

  public GenericAcknowledgmentType querySummaryConfirmed(QuerySummaryConfirmedType querySummaryConfirmed, Holder<CommonHeaderType> header) throws ServiceException {
    QuerySummary q = new QuerySummary(reservationService);
    q.process(querySummaryConfirmed, header);
    return FACTORY.createGenericAcknowledgmentType();
  }

  public GenericAcknowledgmentType queryRecursiveConfirmed(QueryRecursiveConfirmedType queryRecursiveConfirmed,
          Holder<CommonHeaderType> header) throws ServiceException {
    //TODO implement this method
    throw new UnsupportedOperationException("Not implemented yet.");
  }

  /*
  public GenericAcknowledgmentType queryRecursiveConfirmed(QueryRecursiveConfirmedType queryRecursiveConfirmed,
          Holder<CommonHeaderType> header) throws ServiceException {

    log.debug("[ConnectionService] queryRecursiveConfirmed: reservationService = {}", reservationService);

    // Get the providerNSA identifier.
    String providerNsa = header.value.getProviderNSA();

    // Extract the uPA connection segments associated with individual networks.
    List<QueryRecursiveResultType> reservations = queryRecursiveConfirmed.getReservation();
    log.info("[ConnectionService] queryRecursiveConfirmed: providerNSA = {}, # of reservations = {}",
            providerNsa, reservations.size());

    // Process each reservation returned.
    for (QueryRecursiveResultType reservation : reservations) {
      // Get the parent reservation information to apply to child connections.
      ReservationStateEnumType reservationState = reservation.getConnectionStates().getReservationState();
      DataPlaneStatusType dataPlaneStatus = reservation.getConnectionStates().getDataPlaneStatus();
      log.info("[ConnectionService] queryRecursiveConfirmed: cid = {}, gid = {}, state = {}",
              reservation.getConnectionId(), reservation.getGlobalReservationId(), reservationState);

      processRecursiveCriteria(providerNsa, reservation.getGlobalReservationId(), reservation.getConnectionId(),
              reservationState, dataPlaneStatus, reservation.getCriteria());
    }

    return FACTORY.createGenericAcknowledgmentType();
  }

  private void processRecursiveCriteria(String providerNsa, String gid, String cid, ReservationStateEnumType reservationState, DataPlaneStatusType dataPlaneStatus, List<QueryRecursiveResultCriteriaType> criteriaList) {

    // There will be one criteria for each version of this reservation. We
    // will check to see if there are any new versions than what is already
    // stored.
    for (QueryRecursiveResultCriteriaType criteria : criteriaList) {
      log.info("[ConnectionService] processCriteria: cid = {}, version = {}, serviceType = {}", cid,
              criteria.getVersion(), criteria.getServiceType());

      ChildRecursiveListType children = criteria.getChildren();
      if (children == null || children.getChild().isEmpty()) {
        // We are at a leaf child so check to see if we need to store this reservation information.
        Reservation existing = reservationService.get(providerNsa, cid);
        if (existing != null && existing.getVersion() >= criteria.getVersion()) {
          // We have already stored this so update only if state has changed.
          if (reservationState.compareTo(existing.getReservationState()) != 0
                  || dataPlaneStatus.isActive() != existing.isDataPlaneActive()) {
            existing.setReservationState(reservationState);
            existing.setDataPlaneActive(dataPlaneStatus.isActive());
            existing.setDiscovered(System.currentTimeMillis());
            reservationService.update(existing);
          }
          continue;
        }

        Reservation reservation = new Reservation();
        reservation.setDiscovered(System.currentTimeMillis());
        reservation.setGlobalReservationId(gid);
        reservation.setProviderNsa(providerNsa);
        reservation.setConnectionId(cid);
        reservation.setVersion(criteria.getVersion());
        reservation.setServiceType(criteria.getServiceType().trim());
        reservation.setStartTime(getStartTime(criteria.getSchedule().getStartTime()));
        reservation.setEndTime(getEndTime(criteria.getSchedule().getEndTime()));
        // Now we need to determine the network based on the STP used in the service. if
        (Nsi.NSI_SERVICETYPE_EVTS.equalsIgnoreCase(reservation.getServiceType())
                || Nsi.NSI_SERVICETYPE_EVTS_OPENNSA.equalsIgnoreCase(reservation.getServiceType())) {
          reservation.setServiceType(Nsi.NSI_SERVICETYPE_EVTS);
          for (Object any : criteria.getAny()) {
            if (any instanceof JAXBElement) {
              JAXBElement jaxb = (JAXBElement) any;
              if (jaxb.getDeclaredType() == P2PServiceBaseType.class) {
                log.debug("[ConnectionService] processRecursiveCriteria: found P2PServiceBaseType");
                reservation.setService(XmlUtilities.jaxbToString(P2PServiceBaseType.class, jaxb));

                // Get the network identifier from and STP. P2PServiceBaseType p2p = (P2PServiceBaseType) jaxb.getValue();
                SimpleStp stp = new SimpleStp(p2p.getSourceSTP());
                reservation.setTopologyId(stp.getNetworkId());
                break;
              }
            }
          }
        }

        // Replace the existing entry with this new criteria if we already have one. if (existing != null) {
        reservation.setId(existing.getId());
        reservationService.update(reservation);
      } else {
        reservationService.create(reservation);
      }
    }else { // We still have children so this must be an aggregator.
   children.getChild().forEach((child) -> { child.getConnectionStates(); processRecursiveCriteria(
   child.getProviderNSA(), gid, child.getConnectionId(), child.getConnectionStates().getReservationState(),
   child.getConnectionStates().getDataPlaneStatus(), child.getCriteria()); }); }
  }
}

*/

private long getStartTime(JAXBElement<XMLGregorianCalendar> time) {
    if (time == null || time.getValue() == null) {
      return 0;
    }

    return time.getValue().toGregorianCalendar().getTimeInMillis();
  }

  private long getEndTime(JAXBElement<XMLGregorianCalendar> time) {
    if (time == null || time.getValue() == null) {
      return Long.MAX_VALUE;
    }

    return time.getValue().toGregorianCalendar().getTimeInMillis();
  }

  public GenericAcknowledgmentType queryNotificationConfirmed(QueryNotificationConfirmedType queryNotificationConfirmed, Holder<CommonHeaderType> header) throws ServiceException {
    //TODO implement this method
    throw new UnsupportedOperationException("Not implemented yet.");
  }

  public GenericAcknowledgmentType queryResultConfirmed(QueryResultConfirmedType queryResultConfirmed, Holder<CommonHeaderType> header) throws ServiceException {
    //TODO implement this method
    throw new UnsupportedOperationException("Not implemented yet.");
  }

  public GenericAcknowledgmentType error(GenericErrorType error, Holder<CommonHeaderType> header) throws ServiceException {
    CommonHeaderType value = header.value;
    String connectionId = error.getServiceException().getConnectionId();

    log.info("[ConnectionService] error received for correlationId = {}, connectionId: {}",
            value.getCorrelationId(), connectionId);

    // We need to inform the requesting thread of the error.
    Operation op = operationMap.get(value.getCorrelationId());
    if (op == null) {
      log.error("[ConnectionService] error can't find outstanding operation for correlationId = {}",
              value.getCorrelationId());
    } else {
      op.setState(StateType.failed);
      op.setException(error.getServiceException());
      op.getCompleted().release();
    }

    return FACTORY.createGenericAcknowledgmentType();
  }

  public GenericAcknowledgmentType errorEvent(ErrorEventType errorEvent, Holder<CommonHeaderType> header) throws ServiceException {
    //TODO implement this method
    throw new UnsupportedOperationException("Not implemented yet.");
  }

  public GenericAcknowledgmentType dataPlaneStateChange(
          DataPlaneStateChangeRequestType dataPlaneStateChange,
          Holder<CommonHeaderType> header) throws ServiceException {

    String connectionId = dataPlaneStateChange.getConnectionId();
    DataPlaneStatusType dataPlaneStatus = dataPlaneStateChange.getDataPlaneStatus();

    log.info("[ConnectionService] dataPlaneStateChange for connectionId = {}, notificationId = {}, "
            + "active = {}, consistent = {}, time = {}",
            connectionId,
            dataPlaneStateChange.getNotificationId(),
            dataPlaneStatus.isActive(),
            dataPlaneStatus.isVersionConsistent(),
            dataPlaneStateChange.getTimeStamp());

    // This state change is in the context of the local providerNSA so we must
    // assume we are directly connect to a uPA in order for us to map this
    // incoming event to the associated connection.  If we are connected to an
    // aggregator then the connectionId we want is actually a child connection.
    // Find the associated connection.
    Reservation r = reservationService.get(header.value.getProviderNSA(), connectionId);
    if (r == null) {
      log.error("[ConnectionService] dataPlaneStateChange could not find connectionId = {}", connectionId);
    } else {
      r.setDataPlaneActive(dataPlaneStatus.isActive());
      r.setDiscovered(System.currentTimeMillis());
      reservationService.store(r);
    }
    return FACTORY.createGenericAcknowledgmentType();
  }

  public GenericAcknowledgmentType reserveTimeout(ReserveTimeoutRequestType reserveTimeout, Holder<CommonHeaderType> header) throws ServiceException {
    String connectionId = reserveTimeout.getConnectionId();
    String providerNSA = header.value.getProviderNSA();

    log.error("[ConnectionService] reserveTimeout for correlationId = {}, connectionId = {}, providerNSA = {}",
            header.value.getCorrelationId(), connectionId);

    log.error("[ConnectionService] reserveTimeout from originatingNSA = {}, originatingConnectionId = {}, timeoutValue = {}",
            reserveTimeout.getOriginatingNSA(), reserveTimeout.getOriginatingConnectionId(),
            reserveTimeout.getTimeoutValue());

    // We can fail the delta request based on this.  We do not have an outstanding
    // operation (or may have one just starting) so no operation to correltate to.
    Reservation r = reservationService.get(providerNSA, connectionId);
    if (r == null) {
      log.error("[ConnectionService] reserveTimeout could not find connectionId = {}", connectionId);
    } else {
      r.setReservationState(ReservationStateEnumType.RESERVE_TIMEOUT);
      r.setLifecycleState(LifecycleStateEnumType.FAILED);
      r.setDiscovered(System.currentTimeMillis());
      reservationService.store(r);
    }
    return FACTORY.createGenericAcknowledgmentType();
  }

  public GenericAcknowledgmentType messageDeliveryTimeout(MessageDeliveryTimeoutRequestType messageDeliveryTimeout, Holder<CommonHeaderType> header) throws ServiceException {
    CommonHeaderType value = header.value;
    log.info("[ConnectionService] messageDeliveryTimeout recieved for correlationId = {}, connectionId: {}",
            value.getCorrelationId(), messageDeliveryTimeout.getConnectionId());
    Operation op = operationMap.get(value.getCorrelationId());
    if (op == null) {
      log.error("[ConnectionService] messageDeliveryTimeout can't find outstanding operation for correlationId = {}",
              value.getCorrelationId());
    } else {
      op.setState(StateType.failed);
      ServiceExceptionType sex = new ServiceExceptionType();
      sex.setNsaId(value.getProviderNSA());
      sex.setText("messageDeliveryTimeout received");
      sex.setConnectionId(messageDeliveryTimeout.getConnectionId());
      op.setException(sex);
      op.getCompleted().release();
    }

    return FACTORY.createGenericAcknowledgmentType();
  }
}
