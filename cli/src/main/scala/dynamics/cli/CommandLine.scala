// Copyright (c) 2017 aappddeevv@gmail.com
// This software is licensed under the MIT License (MIT).
// For more information see LICENSE or https://opensource.org/licenses/MIT

package dynamics
package cli

import scala.scalajs._
import js._
import annotation._
import io.scalajs.nodejs
import io.scalajs.nodejs._
import fs._
import scala.concurrent._
import duration._
import js.DynamicImplicits
import js.Dynamic.{literal => lit}
import scala.util.{Try, Success, Failure}
import fs2._
import scala.concurrent.duration._
import io.scalajs.npm.winston
import io.scalajs.npm.winston._
import io.scalajs.npm.winston.transports._
import cats.implicits._
import cats.effect._
import monocle.Lens
import monocle.macros.GenLens
import monocle.macros.syntax.lens._

import dynamics.common._
import dynamics.client._
import dynamics.http._
import Status._
import Utils._

object CommandLine {
  import scopt._

  type OptionProvider[T] = scopt.OptionParser[T] => Unit

  def mkParser(name: String = "dynamics") = new scopt.OptionParser[AppConfig](name) {
    //override def showUsageOnError = true
    override def terminate(exitState: Either[String, Unit]): Unit = {
      process.exit()
    }
  }

  def withCmd(ac: AppConfig, command: String)    = ac.copy(common = ac.common.copy(command = command))
  def withSub(ac: AppConfig, subcommand: String) = ac.copy(common = ac.common.copy(subcommand = subcommand))

  def general(op: scopt.OptionParser[AppConfig]): Unit = {
    import op._

    note("Common options")
    opt[Unit]("debug")
      .text("Debug level logging")
      .hidden()
      .action((x, c) => c.copy(common = c.common.copy(debug = true)))
    opt[Int]("concurrency")
      .text("General concurrency metric. Default is 4.")
      .action((x, c) => c.copy(common = c.common.copy(concurrency = x)))
    opt[String]("logger-level")
      .hidden()
      .text("Logger level: trace, debug, info, warn or error. Overrides debug option.")
      .action { (x, c) =>
        val newC = c.copy(common = c.common.copy(loggerLevel = Some(x)))
        if (x.toUpperCase == "DEBUG") newC.copy(common = newC.common.copy(debug = true))
        else newC
      }
    opt[Int]("lcid")
      .text("Whenever language choices need to be made, use this LCID.")
      .action((x, c) => c.copy(common = c.common.copy(lcid = x)))
    opt[String]("logfile")
      .text("Logger file.")
      .action((x, c) => c.copy(common = c.common.copy(logFile = x)))
    opt[Unit]('v', "verbose")
      .text("Be verbose.")
      .action((x, c) => c.copy(common = c.common.copy(verbose = true)))
    opt[Unit]('q', "quiet")
      .text("No extra output, just the results of the command.")
      .action((x, c) => c.copy(common = c.common.copy(quiet = true)))
    opt[String]('c', "crm-config")
      .valueName("<file>")
      .text("CRM connection configuration file")
      .action((x, c) => c.copy(common = c.common.copy(crmConfigFile = x)))
    opt[String]("table-format")
      .valueName("honeywell|norc|ramac|void")
      .text("Change the table output format. void = no table adornments.")
      .action((x, c) => c.copy(common = c.common.copy(tableFormat = x)))
    opt[Int]("num-retries")
      .text("Number of retries if a request fails. Default is 5.")
      .action((x, c) => c.copy(common = c.common.copy(numRetries = x)))
    opt[Int]("pause-between")
      .text("Pause between retries in seconds. Default is 10.")
      .action((x, c) => c.copy(common = c.common.copy(pauseBetween = x.seconds)))
      .validate(pause =>
        if (pause < 0 || pause > 60 * 5) failure("Pause must be between 0 and 300 seconds.") else success)
    opt[Int]("request-timeout")
      .text("Request timeout in millis. 1000millis = 1s")
      .action((x, c) => c.copy(common = c.common.copy(requestTimeOutInMillis = Some(x))))
    opt[String]("metadata-cache-file")
      .text("Metadata cache file to use explicitly. Otherwise it is automatically located.")
      .action((x, c) => c.copy(common = c.common.copy(metadataCacheFile = Some(x))))
    opt[Unit]("ignore-metadata-cache")
      .text("Ignore any existing metadata cache. This will cause a new metadata download.")
      .action((x, c) => c.copy(common = c.common.copy(ignoreMetadataCache = true)))
    opt[Int]("batchsize")
      .text("If batching is used, this is the batch size.")
      .action((x, c) => c.copy(common = c.common.copy(batchSize = x)))
    opt[Unit]("batch")
      .text("If set, try to run things using batch OData.")
      .action((x,c) => c.lens(_.common.batch).set(true))
    opt[String]("outputdir")
      .text("Output directory for any content output.")
      .action((x, c) => c.copy(common = c.common.copy(outputDir = x)))
    opt[String]("outputfile")
      .text("Output file.")
      .action((x, c) => c.copy(common = c.common.copy(outputFile = Some(x))))
  }

  /**
    * Instantiate then import the functions to use in your CLI definition.
    */
  case class CliHelpers(protected val op: scopt.OptionParser[AppConfig]) {
    import op._

    def newline(n: Int = 1): Unit = (0 to n).foreach(_ => note("\n"))

    def sub(n: String) = { note("\n"); cmd(n) }

    val mkOutputDir = () =>
      opt[String]("outputdir")
        .text("Output directory for downloads.")
        .action((x, c) => c.lens(_.common.outputDir).set(x))

    val mkNoClobber = () =>
      opt[Unit]("noclobber")
        .text("Do not overwrite existing file.")
        .action((x, c) => c.lens(_.common.noclobber).set(true))

    val mkFilterOptBase = () =>
      opt[Seq[String]]("filter")
        .unbounded()
        .action((x, c) => c.lens(_.common.filter).modify(f => f ++ x))

    val mkFilterOpt = () =>
      opt[Seq[String]]("filter")
        .unbounded()
        .text("Filter with a regex. --filter can be repeated.")
        .action((x, c) => c.lens(_.common.filter).modify(f => f ++ x))

    val mkFields = () =>
      opt[Seq[String]]("fields")
        .text("List of fields to retrieve. Use the Dynamics logical name.")
        .action((x, c) => c.lens(_.common.fields).set(Some(x)))
  }

  def metadata(op: scopt.OptionParser[AppConfig]): Unit = {
    val h = CliHelpers(op)
    import h.sub
    import op._
    cmd("metadata")
      .text("Report out on metadata.")
      .action((x, c) => withCmd(c, "metadata"))
      .children(
        sub("list-entities")
          .text("List all entities and object type codes.")
          .action((x, c) => withSub(c, "listentities")),
        sub("download-csdl")
          .text("Download CSDL.")
          .action((x, c) => withSub(c, "downloadcsdl"))
          .children(
            arg[String]("output-file")
              .text("Output CSDL file.")
              .action((x, c) => c.lens(_.common.outputFile).set(Some(x)))
          ),
        sub("test")
          .text("Run a metadata test.")
          .hidden()
          .action((x, c) => withSub(c, "test")),
        note("Metadata download files are large and may take a while.")
      )
  }

  def whoami(op: scopt.OptionParser[AppConfig]): Unit = {
    val h = CliHelpers(op)
    import h.sub
    import op._
    cmd("whoami")
      .text("Print out WhoAmI information.")
      .action((x, c) => withCmd(c, "whoami"))
  }

  def test(op: scopt.OptionParser[AppConfig]): Unit = {
    import op._
    val h = CliHelpers(op)
    import h.sub
    cmd("test")
      .text("Run some tests...secretly")
      .hidden()
      .action((x, c) => withCmd(c, "__test__"))
      .children(
        opt[String]("arg")
          .text("arg to test routine")
          .action((x, c) => c.lens(_.test.testArg).set(Some(x)))
      )
  }

  def token(op: scopt.OptionParser[AppConfig]): Unit = {
    import op._
    val h = CliHelpers(op)
    import h.sub
    cmd("token")
      .text("Manage tokens.")
      .action((x, c) => withCmd(c, "token"))
      .children(
        sub("get-one")
          .text("Get one token and output to a file.")
          .action((x, c) => withSub(c, "getOne"))
          .children(
            opt[String]("outputfile")
              .text("Output file. Will overwrite.")
              .action((x, c) => c.lens(_.common.outputFile).set(Some(x)))
          ),
        sub("get-many")
          .text("Get a token and output it as json to a file. Renew and overwrite automatically.")
          .action((x, c) => withSub(c, "getMany"))
          .children(
            opt[String]("outputfile")
              .text("Output file. Will overwrite.")
              .action((x, c) => c.lens(_.common.outputFile).set(Some(x)))
          ),
        note("Default token output file is crm-token.json.")
      )

  }

  def update(op: scopt.OptionParser[AppConfig]): Unit = {
    import op._
    val helpers = CliHelpers(op)
    import helpers._

    cmd("update")
      .text(
        "Update dynamics records. An updated can also be an insert. Attribute processing order is drops, renames then keeps.")
      .action((x, c) => withCmd(c, "update"))
      .children(
        sub("entity")
          .text("Update data in dynamics.")
          .action((x, c) => withSub(c, "data"))
          .children(
            arg[String]("entity")
              .text("Entity to update. Use the entity logical name which is usually lowercase.")
              .action((x, c) => c.copy(update = c.update.copy(updateEntity = x))),
            arg[String]("inputfile")
              .text("JSON streaming data file. JSON records separated by newlines.")
              .action((x, c) => c.copy(update = c.update.copy(updateDataInputCSVFile = x))),
            opt[Boolean]("upsertpreventcreate")
              .text("Prevent a create if the record to update is not present. Default is true.")
              .action((x, c) => c.copy(update = c.update.copy(upsertPreventCreate = x))),
            opt[Boolean]("upsertpreventupdate")
              .text("If you are inserting data using an update operation  and the record already exists, do not update. The default is false.")
              .action((x, c) => c.copy(update = c.update.copy(upsertPreventUpdate = x))),
            opt[String]("pk")
              .text("Name of PK in the data input. Defaults to id (case insensitive.")
              .action((x, c) => c.copy(update = c.update.copy(updatePKColumnName = x))),
            opt[Seq[String]]("drops")
              .text("Drop columns. Logical column names separate by commas. Can be specifed multiple times.")
              .action((x, c) => c.copy(update = c.update.copy(updateDrops = c.update.updateDrops ++ x))),
            opt[Seq[String]]("keeps")
              .text("Keep columns. Logical column names separate by commas. Can be specifed multiple times.")
              .action((x, c) => c.copy(update = c.update.copy(updateKeeps = c.update.updateKeeps ++ x))),
            opt[Int]("take")
              .text("Process only N records.")
              .action((x, c) => c.copy(update = c.update.copy(updateTake = Some(x)))),
            opt[Int]("drop")
              .text("Drop first N records.")
              .action((x, c) => c.copy(update = c.update.copy(updateDrop = Some(x)))),
            opt[Seq[String]]("renames")
              .text("Rename columns. Paris of oldname=newname, separate by commas. Can be specified multiple times.")
              .action { (x, c) =>
                val pairs = x.map(p => p.split('=')).map { a =>
                  if (a.size != 2 || (a(0).size == 0 || a(1).size == 0))
                    throw new IllegalArgumentException(s"Each rename pair of values must be separated by '=': $a.")
                  (a(0), a(1))
                }
                c.copy(update = c.update.copy(updateRenames = c.update.updateRenames ++ pairs))
              },
            note(
              "This is command is really only useful for updating/inserting data exported using the export command."),
            note("Both configuration data or Dynamics entity in json format can be used. Processing performance is good even for large datasets.")
          ),
        sub("test")
          .text("Test some update stuff")
          .action((x, c) => withSub(c, "test"))
          .hidden()
      )
  }

  def entity(op: scopt.OptionParser[AppConfig]): Unit = {
    import op._
    val helpers = CliHelpers(op)
    import helpers._

    val formattedValues = opt[Unit]("include-formatted-values")
      .text("Include formmated values in the output. This increases the size significantly.")
      .action((x, c) => c.lens(_.export.exportIncludeFormattedValues).set(true))

    val top = opt[Int]("top")
      .text("Top N records.")
      .action((x, c) => c.lens(_.export.exportTop).set(Option(x)))

    val skip = opt[Int]("skip")
      .text("Skip N records.")
      .action((x, c) => c.lens(_.export.exportSkip).set(Option(x)))

    val maxPageSize = opt[Int]("maxpagesize")
      .text(
        "Set the maximum number of entities returned per 'fetch'. If node crashes when exporting large entities, set this smaller than 5000.")
      .action((x, c) => c.lens(_.export.exportMaxPageSize).set(Option(x)))

    cmd("entity")
      .text("Query/delete entity data from CRM using parts of a query that is assembled into a web api query.")
      .action((x, c) => withCmd(c, "entity"))
      .children(
        sub("export")
          .text("Export entity data.")
          .action((x, c) => withSub(c, "export"))
          .children(
            arg[String]("entity")
              .text("Entity to export. Use the entity set collection logical name (the plural name) which is usually all lowercase and pluralized (s or es appended).")
              .action((x, c) => c.lens(_.export.exportEntity).set(x)),
            opt[String]("fetchxml")
              .text("Fetch XML for query focused on entity.")
              .action((x, c) => c.lens(_.export.exportFetchXml).set(Option(x))),
            top,
            skip,
            opt[String]("filter")
              .text("Filter criteria using web api format. Do not include $filter.")
              .action((x, c) => c.lens(_.export.exportFilter).set(Option(x))),
            opt[Seq[String]]("orderby")
              .text("Order by criteria e.g. createdon desc. Attribute must be included for download if you are using select. Multiple order bys can be specified in same option, separate by commas.")
              .action((x, c) => c.lens(_.export.exportOrderBy).set(x)),
            opt[Unit]("raw")
              .text("Instead of dumping a CSV file, dump one raw json record so you can see what attributes are available. Add a dummy select field to satisfy CLI args.")
              .action((x, c) => c.lens(_.export.exportRaw).set(true)),
            opt[Seq[String]]("select")
              .text("Select criteria using web api format. Do not include $select.")
              .action((x, c) => c.lens(_.export.exportSelect).set(x)),
            maxPageSize,
            formattedValues
          ),
        sub("export-from-query")
          .text("Export entities from a raw web api query. Only raw json export is supported.")
          .action((x, c) => withSub(c, "exportFromQuery"))
          .children(
            arg[String]("query")
              .text("Web api format query string e.g. /contacts?$filter=...")
              .action((x, c) => c.lens(_.export.entityQuery).set(x)),
            opt[Unit]("wrap")
              .text("Wrap the entire output in an array.")
              .action((x, c) => c.lens(_.export.exportWrap).set(true)),
            formattedValues,
            maxPageSize,
            skip
          ),
        sub("count")
          .text("Count entities. Concurrency affects how many counters run simultaneously.")
          .action((x, c) => withSub(c, "count"))
          .children(
            opt[Unit]("repeat")
              .text("Repeat forever.")
              .action((x, c) => c.lens(_.export.exportRepeat).set(true)),
            mkFilterOptBase().required().text("List of entity names to count. Use entity logical names.")
          ),
        sub("delete-by-query")
          .text("Delete entities based on a query. This is very dangerous to use. Query must return primary key at the very least.")
          .action((x, c) => withSub(c, "deleteByQuery"))
          .children(
            arg[String]("query")
              .text("Web api format query string e.g. /contacts?$select=...&$filter=...")
              .action((x, c) => c.lens(_.export.entityQuery).set(x)),
            arg[String]("entity")
              .text("Entity to delete. We need this to identify the primary key")
              .action((x, c) => c.lens(_.export.exportEntity).set(x))
          ),
        note("""Skip must read records then skip them.""")
      )

  }

  def importdata(op: scopt.OptionParser[AppConfig]): Unit = {
    import op._

    val helpers = CliHelpers(op)
    import helpers._

    cmd("importdata")
      .text("Import data using the CRM Data Import capability. Limited but still very useful.")
      .action((x, c) => withCmd(c, "importdata"))
      .children(
        sub("list-imports")
          .text("List all imports.")
          .action((x, c) => withSub(c, "listimports")),
        sub("list-importfiles")
          .text("List all import filess.")
          .action((x, c) => withSub(c, "listimportfiles")),
        sub("bulkdelete")
          .text("Delete an import by the import id.")
          .action((x, c) => withSub(c, "bulkdelete"))
          .children(
            arg[String]("jobname")
              .text("Job name. This will appear in system jobs.")
              .action((x, c) => c.lens(_.importdata.importDataDeleteJobName).set(x)),
            arg[String]("importid")
              .text("Import id of import that loaded the data you want to delete.")
              .action((x, c) => c.lens(_.importdata.importDataDeleteImportId).set(x)),
            opt[String]("startat")
              .text("Start time in GMT offset. Use -5 for EST e.g. 2017-01-01T11:00:00Z is 6am EST. Default is 3 minutes from now.")
              .action((x, c) => c.lens(_.importdata.importDataDeleteStartTime).set(Option(x))),
            opt[String]("queryjson")
              .text("Query expression in json format. It should be an array: [{q1}, {q2},...]")
              .action((x, c) => c.lens(_.importdata.importDataDeleteQueryJson).set(Option(x))),
            opt[String]("recurrence")
              .text("Recurrence pattern using RFC2445. Default is to run once, empty string.")
              .action((x, c) => c.lens(_.importdata.importDataDeleteRecurrencePattern).set(Option(x)))
          ),
        sub("delete")
          .text("Delete imports")
          .action((x, c) => withSub(c, "delete"))
          .children(
            mkFilterOpt().required()
          ),
        sub("import")
          .text("Import data.")
          .action((x, c) => withSub(c, "import"))
          .children(
            arg[String]("<inputfile file>")
              .text("CSV data file.")
              .action((x, c) => c.lens(_.importdata.importDataInputFile).set(x)),
            arg[String]("<importmap file>")
              .text("Import map name, must already be loaded in CRM.")
              .action((x, c) => c.lens(_.importdata.importDataImportMapName).set(x)),
            opt[String]('n', "name")
              .text("Set the job name. A name is derived otherwise.")
              .action((x, c) => c.lens(_.importdata.importDataName).set(Option(x))),
            opt[Int]("polling-interval")
              .text("Polling interval in seconds for job completion tracking. Default is 60 seconds.")
              .action((x, c) => c.lens(_.importdata.importDataPollingInterval).set(x))
              .validate(x =>
                if (x < 1 || x > 60) failure("Polling frequency must be between 1 and 60 seconds.") else success),
            opt[Unit]("update")
              .text("Import data in update mode. Default is create mode. I don't think this works.")
              .action((x, c) => c.lens(_.importdata.importDataCreate).set(false)),
            opt[Boolean]("dupedetection")
              .text("Enable disable duplication detectiond. Default is disabled!.")
              .action((x, c) => c.lens(_.importdata.importDataEnableDuplicateDetection).set(x))
          ),
        sub("resume")
          .text("Resume a data import at the last processing stage.")
          .action((x, c) => withSub(c, "resume"))
          .children(
            opt[String]("importid")
              .text("importid to resume on.")
              .required()
              .action((x, c) => c.lens(_.importdata.importDataResumeImportId).set(x)),
            opt[String]("importfileid")
              .text("importfiled to resume on.")
              .required()
              .action((x, c) => c.lens(_.importdata.importDataResumeImportFileId).set(x))
          ),
        note("A log file may be created based on the import data file (NOT IMPLEMENTED YET)."),
        note("You can upload an import map using the cli command 'importmap upload'."),
        note("If your cli is cutoff from the server, resume the processing using the 'importdata resume' command."),
        note("For bulk delete query json syntax see: https://msdn.microsoft.com/en-us/library/mt491192.aspx"),
      )
  }

  def importmaps(op: scopt.OptionParser[AppConfig]): Unit = {
    import op._
    val helpers = CliHelpers(op)
    import helpers._

    cmd("importmaps")
      .text("Manage import maps")
      .action((x, c) => withCmd(c, "importmaps"))
      .children(
        note("\n"),
        sub("list")
          .text("List import maps.")
          .action((x, c) => withSub(c, "list")),
        mkFilterOptBase().text("Filter on import map names and descriptions."),
        sub("download")
          .text("Download import maps.")
          .action((x, c) => withSub(c, "download"))
          .children(
            mkNoClobber(),
            mkOutputDir()
          ),
        sub("upload")
          .text("Upload a map.")
          .action((x, c) => withSub(c, "upload"))
          .children(
            arg[String]("<file>...")
              .text("Upload files.")
              .unbounded()
              .action((x, c) => c.lens(_.importdata.importMapUploadFilename).modify(f => f :+ x)),
            mkNoClobber()
          )
      )
  }

 def etl(op: scopt.OptionParser[AppConfig]): Unit = {
    import op._
    cmd("etl")
      .text("Run a searchone etl program.")
      .action((x, c) => withCmd(c, "etl"))
      .children(
        arg[String]("etlid")
          .text("Select the specific etl program to run.")
          .action((x, c) => c.lens(_.etl.name).set(x)),
        opt[String]("inputfile")
          .text("Input CSV file.")
          .action((x, c) => c.lens(_.etl.dataInputFile).set(x)),
        opt[String]("etl-params")
          .text("ETL parameters file.")
          .action((x, c) => c.lens(_.etl.paramsFile).set(Option(x))),
        opt[Int]("etl-verbosity")
          .text("Verbosity for ETL logging. Default is 0 or off.")
          .action((x, c) => c.lens(_.etl.verbosity).set(x)),
        opt[Int]("take")
          .text("Process only N records.")
          .action((x, c) => c.lens(_.etl.take).set(Some(x))),
        opt[Int]("drop")
          .text("Drop first N records.")
          .action((x, c) => c.lens(_.etl.drop).set(Some(x))),
        opt[Map[String, String]]("params")
          .valueName("key1=val1,key2=val2...")
          .text("Provide parameters using key-value syntax.")
          .action((x, c) => c.lens(_.etl.cliParameters).set(x)),
        opt[Int]("maxpagesize")
          .text("Set the maximum number of entities returned per 'fetch'. If node crashes when exporting large entities, set this smaller than 5000.")
          .action((x, c) => c.lens(_.etl.maxPageSize).set(Option(x))),
        opt[Unit]("batch")
          .text("Use batch interface. See batchsize.")
          .action((x, c) => c.lens(_.etl.batch).set(true)),
        opt[Unit]("dryrun")
          .text("Do not perform final remote operation. Allows user to check processing flow.")
          .action((x, c) => c.lens(_.etl.dryRun).set(true)),
        opt[String]("query")
          .text("Query to run against a DB.")
          .action((x, c) => c.lens(_.etl.query).set(Some(x))),
        opt[String]("query-file")
          .text("File that holds a query to run.")
          .action((x, c) => c.lens(_.etl.queryFile).set(Some(x))),
        opt[String]("connection-url")
          .text("Connecition URL.")
          .action((x, c) => c.lens(_.etl.connectionUrl).set(Some(x))),
        opt[String]("connection-file")
          .text("Connecition file. See https://www.npmjs.com/package/mssql#connection-pools for json format.")
          .action((x, c) => c.lens(_.etl.connectionFile).set(Some(x))),
      )
  }

  def solutions(op: scopt.OptionParser[AppConfig]): Unit = {
    import op._
    val h = CliHelpers(op)
    import h.sub
    cmd("solutions")
      .text("Manage solutions.")
      .action((x, c) => withCmd(c, "solutions"))
      .children(
        note("\n"),
        sub("list")
          .text("List solutions")
          .action((x, c) => withSub(c, "list")),
        sub("upload")
          .text("Upload a solution.")
          .action((x, c) => withSub(c, "upload"))
          .children(
            arg[String]("solution")
              .text("Solution file. Typically with . zip extension.")
              .action((x, c) => c.lens(_.solution.solutionUploadFile).set(x)),
            opt[String]("config-file")
              .text("Config file in json format.")
              .action((x, c) => c.lens(_.solution.solutionJsonConfigFile).set(Option(x))),
            opt[Unit]("skip-publishing-workflows")
              .text("Publish workflows.")
              .action((x, c) => c.lens(_.solution.solutionPublishWorkflows).set(false))
          ),
        sub("export")
          .text("Export a solution.")
          .action((x, c) => withSub(c, "export"))
          .children(
            arg[String]("solution")
              .text("Solution name.")
              .action((x, c) => c.lens(_.solution.solutionName).set(x)),
            opt[String]("config-file")
              .text("Config file in json format.")
              .action((x, c) => c.lens(_.solution.solutionJsonConfigFile).set(Option(x))),
            opt[Unit]("managed")
              .text("Export as managed solution.")
              .action((x, c) => c.lens(_.solution.solutionExportManaged).set(true))
          ),
        sub("delete")
          .text("Delete a solution")
          .action((x, c) => withSub(c, "delete"))
          .children(
            arg[String]("solution")
              .text("Solution unique name")
              .action((x, c) => c.lens(_.solution.solutionName).set(x))
          )
      )
  }

  def publishers(op: scopt.OptionParser[AppConfig]): Unit = {
    import op._
    val h = CliHelpers(op)
    import h.sub
    cmd("publishers")
      .text("Manage publishers.")
      .action((x, c) => withCmd(c, "publishers"))
      .children(
        note("\n"),
        sub("list")
          .text("List publishers.")
          .action((x, c) => withSub(c, "list"))
      )
  }

  def systemjobs(op: scopt.OptionParser[AppConfig]): Unit = {
    import op._
    val helpers = CliHelpers(op)
    import helpers._

    cmd("systemjobs")
      .text("Manage system jobs.")
      .action((x, c) => withSub(c, "asyncoperations"))
      .children(
        note("\n"),
        sub("list")
          .text("List operations.")
          .action((x, c) => withSub(c, "list"))
          .children(mkFilterOpt()),
        sub("delete-completed")
          .text("Delete completed system jobs.")
          .action((x, c) => withSub(c, "deleteCompleted")),
        sub("delete-canceled")
          .text("Delete canceled system jobs.")
          .action((x, c) => withSub(c, "deleteCanceled")),
        sub("delete-failed")
          .text("Delete failed system jobs.")
          .action((x, c) => withSub(c, "deleteFailed")),
        sub("delete-waiting")
          .text("Delete waiting system jobs.")
          .action((x, c) => withSub(c, "deleteWaiting")),
        sub("delete-waiting-for-resources")
          .text("Delete waiting system jobs.")
          .action((x, c) => withSub(c, "deleteWaitingForResources")),
        sub("delete-inprogress")
          .text("Delete in-progress system jobs.")
          .action((x, c) => withSub(c, "deleteInProgress")),
        sub("cancel")
          .text("Cancel some system jobs based on a name regex.")
          .action((x, c) => withSub(c, "cancel"))
          .children(
            mkFilterOpt().required()
          )
      )
  }

  def workflows(op: scopt.OptionParser[AppConfig]): Unit = {
    import op._
    val helpers = CliHelpers(op)
    import helpers._

    cmd("workflows")
      .text("Manage workflows.")
      .action((x, c) => withCmd(c, "workflows"))
      .children(
        note("\n"),
        sub("list")
          .text("List workflows.")
          .action((x, c) => withSub(c, "list"))
          .children(mkFilterOpt()),
        sub("execute")
          .text("Execute a workflow against the results of a query. A cache can be used.")
          .action((x, c) => withSub(c, "execute"))
          .children(
            arg[String]("id")
              .text("Id of workflow to execute. Use the one with type=1 => template.")
              .action((x, c) => c.lens(_.workflow.workflowIds).modify(ids => ids :+ x)),
            arg[String]("odataquery")
              .text("Use an OData query to select entities to execute against.")
              .action((x, c) => c.lens(_.workflow.workflowODataQuery).set(Some(x))),
            opt[String]("pk")
              .text("Id column name for the entity.")
              .required()
              .action((x, c) => c.lens(_.workflow.workflowPkName).set(x))
              .required(),
            opt[Unit]("batch").text("Run this batch.")
              action ((x, c) => c.lens(_.workflow.workflowBatch).set(true)),
            opt[Unit]("cache")
              .text("Use a local cache.")
              .action((x, c) => c.lens(_.workflow.workflowCache).set(true)),
            opt[String]("cache-file")
              .text("Cache file to use. Otherwise it is automatically named based on the pk.")
              .action((x, c) => c.lens(_.workflow.workflowCacheFilename).set(Some(x))),
            note(
              "The ODataQuery should return the id of the entity e.g. '/contacts?$select=contactid'  --pk contactid.")
          ),
        sub("change-activation")
          .text("Change activation of a workflow.")
          .action((x, c) => withSub(c, "changeactivation"))
          .children(
            opt[Seq[String]]("id")
              .text("Ids of workflow to activate. Obtain from using 'list'. Comma separated or repeat option.")
              .unbounded()
              .action((x, c) => c.lens(_.workflow.workflowIds).modify(ids => ids ++ x)),
            //opt[Seq[String]]("names").
            //  text("Names of workflows. Comma separated or repeat option.").
            //  unbounded().
            //  action((x, c) => c.copy(workflowNames = c.workflowNames ++ x)),
            mkFilterOpt(),
            arg[Boolean]("activate")
              .text("true of false to activate/deactivate.")
              .action((x, c) => c.lens(_.workflow.workflowActivate).set(x))
          ),
        note(
          "A workflow is typically very slow to run. The best option is to not run it again if its already been run."),
        note("A simple cache can be optionally used to skip the entities that the workflow has already run against.")
      )

  }

  def optionsets(op: scopt.OptionParser[AppConfig]): Unit = {
    import op._
    val helpers = CliHelpers(op)
    import helpers._

    cmd("optionsets")
      .text("OptionSet management.")
      .action((x, c) => withCmd(c, "optionsets"))
      .children(
        sub("list")
          .text("List global option sets.")
          .action((x, c) => withSub(c, "list"))
          .children(mkFilterOpt())
      )
  }

  def sdkmessages(op: scopt.OptionParser[AppConfig]): Unit = {
    import op._
    val helpers = CliHelpers(op)
    import helpers._

    cmd("sdkmessages")
      .text("Manage SDK messages.")
      .action((x, c) => withCmd(c, "sdkmessageprocessingsteps"))
      .children(
        sub("list")
          .text("List SDK messages processing step.")
          .action((x, c) => withSub(c, "list"))
          .children(mkFilterOpt()),
        sub("deactivate")
          .text("Deactivate a SDK message by id or a filter.")
          .action((x, c) => withSub(c, "deactivate"))
          .children(
            opt[String]("crmid")
              .text("Id to deactivate.")
              .action((x, c) => c.lens(_.sdkMessage.id).set(x)),
            mkFilterOpt()
          ),
        sub("activate")
          .text("Activate a SDK message by id or a filter.")
          .action((x, c) => withSub(c, "activate"))
          .children(
            opt[String]("crmid")
              .text("Id to deactivate.")
              .action((x, c) => c.lens(_.sdkMessage.id).set(x)),
            mkFilterOpt()
          )
      )
  }

  def plugins(op: scopt.OptionParser[AppConfig]): Unit = {
    import op._
    val h = CliHelpers(op)
    import h.sub
    cmd("plugins")
      .text("Upload a plugin assembly's content.")
      .action((x, c) => withCmd(c, "plugins"))
      .children(
        sub("upload")
          .text("Upload assembly (.dll) content to an existing plugin")
          .action((x, c) => withSub(c, "upload"))
          .children(
            arg[String]("source")
              .text("Plugin dll file location. Plugin name default is the file basename.")
              .action((x, c) => c.copy(plugin = c.plugin.copy(source = Some(x)))),
            opt[Unit]("watch")
              .text("Watch for changes and upload when the file is changed.")
              .action((x, c) => c.copy(plugin = c.plugin.copy(watch = true)))
          ),
        note("Assembly plugin registration information should be in [source name].json."),
        note("Currently, this function can only replace an existing plugin assembly's content.")
      )
  }

  def webresources(op: scopt.OptionParser[AppConfig]): Unit = {
    import op._
    val helpers = CliHelpers(op)
    import helpers._

    cmd("webresources")
      .text("Manage Web Resources.")
      .action((x, c) => withCmd(c, "webresources"))
      .children(
        note("\n"),
        sub("list")
          .text("List web resources")
          .action((x, c) => withSub(c, "list"))
          .children(
            mkFilterOpt()
          ),
        sub("delete")
          .text("Delete a webresource by its unique name.")
          .action((x, c) => withSub(c, "delete"))
          .children(
            mkFilterOpt(),
            opt[Unit]("nopublish")
              .text("Do not publish the deletion. Default is to publish.")
              .action((x, c) => c.lens(_.webResource.webResourceDeletePublish).set(false)),
            arg[String]("name")
              .text("Regex for Web Resource unique names. Can be repeated. Be very careful with your regexs.")
              .action((x, c) => c.lens(_.webResource.webResourceDeleteNameRegex).modify(f => f :+ x))
          ),
        sub("download")
          .text("Download Web Resources and save them to the filesystem. If there are path separators in the name, create filesystem directories as needed.")
          .action((x, c) => withSub(c, "download"))
          .children(
            mkFilterOpt(),
            mkOutputDir(),
            /*
             opt[Unit]("nodirs")
             .text("Place all downloaded files at the same level in the output directory.")
             .action((x, c) => c.copy(createDirectories = false)),
             */
            mkNoClobber(),
            /*
             opt[Unit]("strip-prefix")
             .text("Strip leading prefix in the first file segment if it ends with _ from all downloaded web resources. This removes the solution prefix.")
             .action((x,c) => c.copy(webResourceDownloadStripPrefix =true))
           */
          ),
        sub("upload")
          .text("Upload web resources and optionally publish.")
          .action((x, c) => withCmd(c, "upload"))
          .children(
            arg[String]("source")
              .unbounded()
              .text("Globs for Web Resource(s). To pull an entire directory, use dir/* where dir is usually a publisher prefix.")
              .action((x, c) => c.lens(_.webResource.webResourceUploadSource).modify(f => f :+ x)),
            opt[String]("prefix")
              .text("Give a source file as a path locally, Use prefix to identify the start of the resource name--almost always the publisher prefix.")
              .action((x, c) => c.lens(_.webResource.webResourceUploadPrefix).set(Option(x))),
            opt[Unit]("nopublish")
              .text("Do not publish the uploaded Web Resources after uploading. Default is to publish.")
              .action((x, c) => c.lens(_.webResource.webResourceUploadPublish).set(false)),
            opt[Unit]("noclobber")
              .text("Do not overwrite existing Web Resources. Default is false, overwrite.")
              .action((x, c) => c.lens(_.webResource.webResourceUploadNoclobber).set(true)),
            opt[String]('s', "unique-solution-name")
              .text("Solution unique name for new resources that are registered. Solution must already exist. Defaults to 'Default' if none provided.")
              .action((x, c) => c.lens(_.webResource.webResourceUploadSolution).set(x)),
            opt[Unit]("noregister")
              .text("Do not register a new Web Resource with the solution if the resource does not already exist. Default is it register.")
              .action((x, c) => c.lens(_.webResource.webResourceUploadRegister).set(false)),
            opt[String]('t', "type")
              .text("Resource type 'extension' (e.g. js or png) if not inferrable from resource name. Will override the filenames prefix for all resources to upload that must be created.")
              .action((x, c) => c.lens(_.webResource.webResourceUploadType).set(Option(x))),
            opt[Unit]("watch")
              .text("Watch source for changes and upload changed files.")
              .action((x, c) => c.lens(_.webResource.webResourceUploadWatch).set(true)),
            opt[String]("ignore")
              .text("When in watch mode, a regex patterns to ignore when watching. Can be repeated.")
              .unbounded()
              .action((x, c) => c.lens(_.webResource.webResourceUploadWatchIgnore).modify(f => f :+ x))
          ),
        note(
          "Sources are glob patterns. If the glob expands to a single resource, the source points to the resource directly and target is the full path in dynamics. Web Resources actually have a flat namespace so directories become slashes in the final name. Web Resources with spaces or hyphens in their names cannot be uploaded. New Web Resources must be associated a solution. Use --prefix to indicate where a OS path should be split in order to generate the resource name which always starts with a publisher prefix. Given a OS path of /tmp/new_/blah.js, --prefix new_ will compute a resource name of new/blah.js. If the resource is /tmp/new_blah.js, --prefix new_ or no prefix will use only the filename (basename.prefix) as the resource name. A webresource cannot be deleted if it is being used, you will get a server error. Remove the webresoruce as a dependency then try to delete it.")
      )
  }

  /** All base options. */
  val stdOps: Seq[scopt.OptionParser[AppConfig] => Unit] = Seq(
    entity,
    general,
    importdata,
    importmaps,
    metadata,
    optionsets,
    plugins,
    publishers,
    sdkmessages,
    solutions,
    systemjobs,
    test,
    token,
    update,
    webresources,
    whoami,
    workflows,
    etl,
  )

  /** Add head and help to a list of options, which by default is all base commands. */
  def addAllOptions(op: scopt.OptionParser[AppConfig],
                    addme: Seq[scopt.OptionParser[AppConfig] => Unit] = stdOps): Unit = {
    import op._

    head("dynamics", "0.1.0")
    addme.foreach { o =>
      o(op)
    }
    version("version")
      .abbr("v")
      .text("Print version")
    help("help")
      .abbr("h")
      .text("dynamics command line tool")
  }

  /**
    * Read connection info from file in json format.
    * If there is an error, cannot read the file or if there is no password in the
    * config file or the enviroment variable DYNAMICS_PASSWORD,
    * return an error string.
    */
  def readConnectionInfo(file: String): Either[String, ConnectionInfo] = {
    import scala.util.{Try, Success, Failure}
    Try { slurpAsJson[ConnectionInfo](file) } match {
      case Success(ci) =>
        // look for password in env variable
        val envpassword = nodejs.process.env.get("DYNAMICS_PASSWORD")
        // use env password if it is not in the config file
        (envpassword orElse ci.password.toOption).fold[Either[String, ConnectionInfo]](
          Left(s"No password found in environment variable DYNAMICS_PASSWORD or config file ${file}.")) { password =>
          ci.password = js.defined(password)
          Right(ci)
        }
      case Failure(t) =>
        Left(s"Unable to read configration file ${file}. ${t.getMessage()}")
    }
  }

  /**
    * Read the connection information or exit the application.
    */
  def readConnectionInfoOrExit(file: String): ConnectionInfo = {
    readConnectionInfo(file) match {
      case Right(c) => c
      case Left(e) =>
        println(e)
        process.exit(1)
        null
    }
  }

}