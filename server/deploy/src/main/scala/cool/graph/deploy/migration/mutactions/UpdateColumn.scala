package cool.graph.deploy.migration.mutactions

import cool.graph.deploy.database.DatabaseMutationBuilder
import cool.graph.shared.models.{Field, Model}
import slick.jdbc.MySQLProfile.api._

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

case class UpdateColumn(projectId: String, model: Model, oldField: Field, newField: Field) extends ClientSqlMutaction {

  override def execute: Future[ClientSqlStatementResult[Any]] = {
    if (shouldUpdateClientDbColumn) {
      // when type changes to/from String we need to change the subpart
      // when fieldName changes we need to update index name
      // recreating an index is expensive, so we might need to make this smarter in the future
      updateFromBeforeStateToAfterState(before = oldField, after = newField)
    } else {
      Future.successful(ClientSqlStatementResult(sqlAction = DBIO.successful(())))
    }
  }

  override def rollback: Some[Future[ClientSqlStatementResult[Any]]] = Some(updateFromBeforeStateToAfterState(before = newField, after = oldField))

  // FIXME: where is the right place to handle this?
//  override def handleErrors =
//    Some({
//      // https://dev.mysql.com/doc/refman/5.5/en/error-messages-server.html#error_er_dup_entry
//      case e: SQLIntegrityConstraintViolationException if e.getErrorCode == 1062 =>
//        ExistingDuplicateDataPreventsUniqueIndex(newField.name)
//    })

  private val shouldUpdateClientDbColumn: Boolean = {
    if (oldField.isScalar)
      oldField.isRequired != newField.isRequired ||
      oldField.name != newField.name ||
      oldField.typeIdentifier != newField.typeIdentifier ||
      oldField.isList != newField.isList ||
      oldField.isUnique != newField.isUnique
    else false
  }

  private def updateFromBeforeStateToAfterState(before: Field, after: Field): Future[ClientSqlStatementResult[Any]] = {

    val hasIndex     = before.isUnique
    val indexIsDirty = before.isRequired != after.isRequired || before.name != after.name || before.typeIdentifier != after.typeIdentifier

    val updateColumnMutation = DatabaseMutationBuilder.updateColumn(
      projectId = projectId,
      tableName = model.name,
      oldColumnName = before.name,
      newColumnName = after.name,
      newIsRequired = after.isRequired,
      newIsUnique = after.isUnique,
      newIsList = after.isList,
      newTypeIdentifier = after.typeIdentifier
    )

    val removeUniqueConstraint =
      Future.successful(DatabaseMutationBuilder.removeUniqueConstraint(projectId = projectId, tableName = model.name, columnName = before.name))

    val addUniqueConstraint = Future.successful(
      DatabaseMutationBuilder.addUniqueConstraint(projectId = projectId,
                                                  tableName = model.name,
                                                  columnName = after.name,
                                                  typeIdentifier = after.typeIdentifier,
                                                  isList = after.isList))

    val updateColumn = Future.successful(updateColumnMutation)

    val updateColumnActions = (hasIndex, indexIsDirty, after.isUnique) match {
      case (true, true, true)  => List(removeUniqueConstraint, updateColumn, addUniqueConstraint)
      case (true, _, false)    => List(removeUniqueConstraint, updateColumn)
      case (true, false, true) => List(updateColumn)
      case (false, _, false)   => List(updateColumn)
      case (false, _, true)    => List(updateColumn, addUniqueConstraint)
    }

    Future.sequence(updateColumnActions).map(sqlActions => ClientSqlStatementResult(sqlAction = DBIO.seq(sqlActions: _*)))

  }
}
