package geotrellis.raster.io.json

import geotrellis.raster._
import geotrellis.raster.histogram.{FastMapHistogram, Histogram}
import geotrellis.vector._
import geotrellis.vector.io._

import spray.json._

object Implicits extends Implicits

trait Implicits extends HistogramJsonFormats {

  implicit object CellTypeFormat extends RootJsonFormat[CellType] {
    def write(cellType: CellType) =
      JsString(cellType.toString)

    def read(value: JsValue): CellType =
      value match {
        case JsString(name) => CellType.fromString(name)
        case _ =>
          throw new DeserializationException("CellType must be a string")
      }
  }

  implicit object RasterExtentFormat extends RootJsonFormat[RasterExtent] {
    def write(rasterExtent: RasterExtent) =
      JsObject(
        "extent" -> rasterExtent.extent.toJson,
        "cols" -> JsNumber(rasterExtent.cols),
        "rows" -> JsNumber(rasterExtent.rows),
        "cellwidth" -> JsNumber(rasterExtent.cellwidth),
        "cellheight" -> JsNumber(rasterExtent.cellheight)
      )

    def read(value: JsValue): RasterExtent =
      value.asJsObject.getFields("extent", "cols", "rows", "cellwidth", "cellheight") match {
        case Seq(extent, JsNumber(cols), JsNumber(rows), JsNumber(cellwidth), JsNumber(cellheight)) =>
          val ext = extent.convertTo[Extent]
          RasterExtent(ext, cellwidth.toDouble, cellheight.toDouble, cols.toInt, rows.toInt)
        case _ =>
          throw new DeserializationException("RasterExtent expected.")
      }
  }

  implicit object TileLayoutFormat extends RootJsonFormat[TileLayout] {
    def write(tileLayout: TileLayout) =
      JsObject(
        "layoutCols" -> JsNumber(tileLayout.layoutCols),
        "layoutRows" -> JsNumber(tileLayout.layoutRows),
        "tileCols" -> JsNumber(tileLayout.tileCols),
        "tileRows" -> JsNumber(tileLayout.tileRows)
      )

    def read(value: JsValue): TileLayout =
      value.asJsObject.getFields("layoutCols", "layoutRows", "tileCols", "tileRows") match {
        case Seq(JsNumber(layoutCols), JsNumber(layoutRows), JsNumber(tileCols), JsNumber(tileRows)) =>
          TileLayout(layoutCols.toInt, layoutRows.toInt, tileCols.toInt, tileRows.toInt)
        case _ =>
          throw new DeserializationException("TileLayout expected.")
      }
  }

  implicit object GridBoundsFormat extends RootJsonFormat[GridBounds] {
    def write(gridBounds: GridBounds) =
      JsObject(
        "colMin" -> JsNumber(gridBounds.colMin),
        "rowMin" -> JsNumber(gridBounds.rowMin),
        "colMax" -> JsNumber(gridBounds.colMax),
        "rowMax" -> JsNumber(gridBounds.rowMax)
      )

    def read(value: JsValue): GridBounds =
      value.asJsObject.getFields("colMin", "rowMin", "colMax", "rowMax") match {
        case Seq(JsNumber(colMin), JsNumber(rowMin), JsNumber(colMax), JsNumber(rowMax)) =>
          GridBounds(colMin.toInt, rowMin.toInt, colMax.toInt, rowMax.toInt)
        case _ =>
          throw new DeserializationException("GridBounds expected.")
      }
  }
}
