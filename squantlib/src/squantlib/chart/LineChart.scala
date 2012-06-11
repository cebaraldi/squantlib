package squantlib.chart

import java.io.File
import java.lang.Number
import org.jfree.chart._
import org.jfree.chart.plot._
import org.jfree.data.xy._

class AbstractChart {
  protected def save(png:String, chart:JFreeChart, width:Integer = 640, height:Integer = 480):String = {
    ChartUtilities.saveChartAsPNG(new File(png), chart, width, height)
    return png
  }
}

object LineChart extends AbstractChart {
  /**
   * Renders a line chart to a PNG file.
   *
   * @param png Path for output file.
   * @param data Data to plot. In Seq[Pair[X, Y]] format.
   * @param x Label for X-axis
   * @param y Label for Y-axis
   * @param title Title for the plot.
   * @return Path to the output file (= png)
   *  
   */
  def render(png:String, data:Seq[Pair[Number, Number]], x:String = "X-axis", y:String = "Y-axis", title:String = "Untitled"):String = {
    val series = new XYSeries(title)
    for (pair <- data) series.add(pair._1, pair._2)
    val dataset = new XYSeriesCollection
    dataset.addSeries(series)
    val chart = ChartFactory.createXYLineChart(
                  title,
                  x, y,
                  dataset,
                  PlotOrientation.VERTICAL,
                  false,
                  false,
                  false
                )
    return save(png, chart)
  }
}