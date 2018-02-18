/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.progress.datadirect;

import org.apache.beam.sdk.Pipeline;
import org.apache.beam.sdk.options.PipelineOptionsFactory;
import org.apache.beam.sdk.transforms.Create;
import org.apache.beam.sdk.transforms.DoFn;
import org.apache.beam.sdk.transforms.MapElements;
import org.apache.beam.sdk.transforms.ParDo;
import org.apache.beam.sdk.transforms.SimpleFunction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.api.services.bigquery.model.TableFieldSchema;
import com.google.api.services.bigquery.model.TableRow;
import com.google.api.services.bigquery.model.TableSchema;

import org.apache.beam.sdk.values.KV;
import org.apache.beam.sdk.values.PCollection;
import org.apache.beam.sdk.io.TextIO;
import org.apache.beam.sdk.io.gcp.bigquery.BigQueryIO;
import org.apache.beam.sdk.io.jdbc.JdbcIO;
import org.apache.beam.sdk.coders.DefaultCoder;
import org.apache.beam.sdk.coders.KvCoder;
import org.apache.beam.sdk.coders.ListCoder;
import org.apache.beam.sdk.coders.MapCoder;
import org.apache.beam.sdk.coders.StringUtf8Coder;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A starter example for writing Beam programs.
 *
 * <p>The example takes two strings, converts them to their upper-case
 * representation and logs them.
 *
 * <p>To run this starter example locally using DirectRunner, just
 * execute it without any additional parameters from your favorite development
 * environment.
 *
 * <p>To run this starter example using managed resource in Google Cloud
 * Platform, you should specify the following command-line options:
 *   --project=<YOUR_PROJECT_ID>
 *   --stagingLocation=<STAGING_LOCATION_IN_CLOUD_STORAGE>
 *   --runner=DataflowRunner
 */
public class StarterPipeline {
  private static final Logger LOG = LoggerFactory.getLogger(StarterPipeline.class);
  private static TableSchema schema = null;

  public static void main(String[] args) {
    Pipeline p = Pipeline.create(
        PipelineOptionsFactory.fromArgs(args).withValidation().create());
    
	PCollection<List<String>> rows = p.apply(JdbcIO.<List<String>>read()
    		.withDataSourceConfiguration(JdbcIO.DataSourceConfiguration.create(
            "com.ddtek.jdbc.ddhybrid.DDHybridDriver", "jdbc:datadirect:ddhybrid://<HDP Server>;hybridDataPipelineDataSource=Oracle")
            .withUsername("USER")
            .withPassword("Password"))
    		.withQuery("SELECT * FROM TEST01.CUSTOMER")
    		.withCoder(ListCoder.of(StringUtf8Coder.of()))
    		.withRowMapper(new JdbcIO.RowMapper<List<String>>() {
                public List<String> mapRow(ResultSet resultSet) throws Exception {
                	
                	List<String> addRow = new ArrayList<String>();
                	//Get the Schema for BigQuery
                	if(schema == null)
                	{
                		 schema = getSchemaFromResultSet(resultSet);
                	}
                	
                	//Creating a List of Strings for each Record that comes back from JDBC Driver.
                	for(int i=1; i<= resultSet.getMetaData().getColumnCount(); i++ )
                	{
                		addRow.add(i-1, String.valueOf(resultSet.getObject(i)));
                	}
                	
                	//LOG.info(String.join(",", addRow));
                	
                	return addRow;
                }
            })
    		)
			
	         .apply(ParDo.of(new DoFn<List<String>, List<String>>() {
	               @ProcessElement
	               //Apply Transformation - Mask the EmailAddresses by Hashing the value
	               public void processElement(ProcessContext c) {
	                   
	            	   List<String> record = c.element();
	 
	            	   List<String> record_copy = new ArrayList(record);
	            	   String hashedEmail = hashemail(record.get(11));
	            	   record_copy.set(11, hashedEmail);
	            	   
	            	   c.output(record_copy);
	            	   
	               }
	           }));
	
	
	 PCollection<TableRow> tableRows =  rows.apply(ParDo.of(new DoFn<List<String>, TableRow>() {
         @ProcessElement
         //Convert the rows to TableRows of BigQuery
         public void processElement(ProcessContext c) {
             
        	 TableRow tableRow = new TableRow();
        	 List<TableFieldSchema> columnNames = schema.getFields();
        	 List<String> rowValues = c.element();
        	 for(int i =0; i< columnNames.size(); i++)
        	 {
        		 tableRow.put(columnNames.get(i).get("name").toString(), rowValues.get(i));
        	 }

             c.output(tableRow);
         }
     }));
	

	 
	//Write Table Rows to BigQuery			
	 tableRows.apply(BigQueryIO.writeTableRows()
			 .withSchema(schema)
       	     .to("nodal-time-161120:Oracle.CUSTOMER")
       	     .withWriteDisposition(BigQueryIO.Write.WriteDisposition.WRITE_APPEND));
       	  	

      p.run();
  }
  
  
  private static TableSchema getSchemaFromResultSet(ResultSet resultSet)
  {
	  FieldSchemaListBuilder fieldSchemaListBuilder = new FieldSchemaListBuilder();
	   
	  try {
		  ResultSetMetaData rsmd = resultSet.getMetaData();
		  for(int i=1; i <= rsmd.getColumnCount(); i++)
		  {
			  fieldSchemaListBuilder.stringField(rsmd.getColumnName(i));
			  //LOG.info(rsmd.getColumnName(i));
		  }
	  }
	  catch (SQLException ex) {
		  LOG.error("Error getting metadata: " + ex.getMessage());
	  }
	  
	  return fieldSchemaListBuilder.schema();
  }

  public static String hashemail(String email)
  {
	  String encoded = null;
	  try
	  {
	  MessageDigest digest = MessageDigest.getInstance("SHA-256");
	  byte[] hash = digest.digest(email.getBytes(StandardCharsets.UTF_8));
	  encoded = Base64.getEncoder().encodeToString(hash);
	  }
	  catch(NoSuchAlgorithmException ex)
	  {
		  LOG.error("Algorithm not found: " + ex.getMessage());
	  }
	  return encoded;
  }
  
  
}
