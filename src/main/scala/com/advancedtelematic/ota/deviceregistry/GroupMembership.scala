package com.advancedtelematic.ota.deviceregistry

import akka.http.scaladsl.util.FastFuture
import com.advancedtelematic.libats.data.DataType.Namespace
import com.advancedtelematic.libats.data.PaginationResult
import com.advancedtelematic.ota.deviceregistry.common.Errors
import com.advancedtelematic.ota.deviceregistry.data.Group.{GroupExpression, GroupId, Name}
import com.advancedtelematic.ota.deviceregistry.data.GroupType.GroupType
import com.advancedtelematic.ota.deviceregistry.data.{Group, GroupType, Uuid}
import com.advancedtelematic.ota.deviceregistry.db.{DeviceRepository, GroupInfoRepository, GroupMemberRepository}
import slick.jdbc.MySQLProfile.api._

import scala.concurrent.{ExecutionContext, Future}

protected trait GroupMembershipOperations {
  def addMember(groupId: GroupId, deviceId: Uuid): Future[Unit]
  def removeMember(group: Group, deviceId: Uuid): Future[Unit]
}

protected class DynamicMembership(implicit db: Database, ec: ExecutionContext) extends GroupMembershipOperations {

  def create(
      groupId: GroupId,
      name: Name,
      namespace: Namespace,
      expression: GroupExpression
  ): Future[GroupId] = db.run {
    GroupInfoRepository
      .create(groupId, name, namespace, GroupType.dynamic, Some(expression))
      .andThen {
        DeviceRepository.searchByDeviceIdContains(namespace, expression).flatMap { devices =>
          DBIO.sequence(devices.map { d =>
            GroupMemberRepository.addGroupMember(groupId, d)
          })
        }
      }
      .map(_ => groupId)
      .transactionally
  }

  override def addMember(groupId: GroupId, deviceId: Uuid): Future[Unit] =
    FastFuture.failed(Errors.CannotAddDeviceToDynamicGroup)

  override def removeMember(group: Group, deviceId: Uuid): Future[Unit] =
    FastFuture.failed(Errors.CannotRemoveDeviceFromDynamicGroup)
}

protected class StaticMembership(implicit db: Database, ec: ExecutionContext) extends GroupMembershipOperations {

  override def addMember(groupId: GroupId, deviceId: Uuid): Future[Unit] =
    db.run(GroupMemberRepository.addGroupMember(groupId, deviceId)).map(_ => ())

  override def removeMember(group: Group, deviceId: Uuid): Future[Unit] =
    db.run(GroupMemberRepository.removeGroupMember(group.id, deviceId))

  def create(
      groupId: GroupId,
      name: Name,
      namespace: Namespace
  ): Future[GroupId] = db.run {
    GroupInfoRepository.create(groupId, name, namespace, GroupType.static, expression = None)
  }
}

class GroupMembership(implicit val db: Database, ec: ExecutionContext) {

  private def runGroupOperation[T](groupId: GroupId)(fn: (Group, GroupMembershipOperations) => Future[T]): Future[T] =
    GroupInfoRepository.findById(groupId).flatMap {
      case g if g.`type` == GroupType.static => fn(g, new StaticMembership())
      case g                                 => fn(g, new DynamicMembership())
    }

  def create(groupId: GroupId,
             name: Group.Name,
             namespace: Namespace,
             groupType: GroupType,
             expression: Option[GroupExpression]) =
    (groupType, expression) match {
      case (GroupType.static, None)       => new StaticMembership().create(groupId, name, namespace)
      case (GroupType.static, exp)        => FastFuture.failed(Errors.InvalidGroupExpressionForGroupType(groupType, exp))
      case (GroupType.dynamic, None)      => FastFuture.failed(Errors.InvalidGroupExpressionForGroupType(groupType, None))
      case (GroupType.dynamic, Some(exp)) => new DynamicMembership().create(groupId, name, namespace, exp)
    }

  def listDevices(groupId: GroupId, offset: Option[Long], limit: Option[Long]): Future[PaginationResult[Uuid]] =
    db.run(GroupMemberRepository.listDevicesInGroup(groupId, offset, limit))

  def addGroupMember(groupId: GroupId, deviceId: Uuid)(implicit ec: ExecutionContext): Future[Unit] =
    runGroupOperation(groupId) { (g, m) =>
      m.addMember(g.id, deviceId)
    }

  def countDevices(groupId: GroupId): Future[Long] = db.run(GroupMemberRepository.countDevicesInGroup(groupId))

  def removeGroupMember(groupId: GroupId, deviceId: Uuid): Future[Unit] = runGroupOperation(groupId) { (g, m) =>
    m.removeMember(g, deviceId)
  }
}
