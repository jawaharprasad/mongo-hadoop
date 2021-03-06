/*
 * Copyright 2011 10gen Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.mongodb.hadoop.pig;

import org.bson.*;
import org.bson.types.*;
import org.bson.types.ObjectId;
import com.mongodb.*;
import com.mongodb.hadoop.*;
import com.mongodb.hadoop.output.*;
import com.mongodb.hadoop.util.*;
import org.apache.commons.logging.*;
import org.apache.hadoop.conf.*;
import org.apache.hadoop.mapreduce.*;
import org.apache.pig.*;
import org.apache.pig.data.*;
import org.apache.pig.impl.util.*;
import org.apache.pig.ResourceSchema;
import org.apache.pig.ResourceSchema.ResourceFieldSchema;


import java.io.*;
import java.text.ParseException;
import java.util.*;
import java.text.SimpleDateFormat;

public class BSONStorage extends StoreFunc implements StoreMetadata {
    
    private static final Log log = LogFactory.getLog( MongoStorage.class );
    static final String SCHEMA_SIGNATURE = "bson.pig.output.schema";
    protected ResourceSchema schema = null;
    private RecordWriter out;
    
    private String udfcSignature = null;
    private String idField = null;
    private boolean useUpsert = false; 
    
    private final BSONFileOutputFormat outputFormat = new BSONFileOutputFormat();
    
    public BSONStorage(){ }
    
    public BSONStorage(String idField){ 
        this.idField = idField;
    }
    
    /*
     * Returns object more suited for BSON storage. Object o corresponds to a field value in pig.
     *
     * @param object o : object representing pig type to convert to BSON-like object
     * @param ResourceFieldSchema field : field to place o in
     * @param String toIgnore : name of field in Object o to ignore
     */
    public static Object getTypeForBSON(Object o, ResourceSchema.ResourceFieldSchema field, String toIgnore) throws IOException{
        byte dataType = field != null ? field.getType() : DataType.UNKNOWN;
        ResourceSchema s = null;
        if( field == null ){
            if(o instanceof Map){
                dataType = DataType.MAP;
            }else if(o instanceof List){ 
                dataType = DataType.BAG;
            } else {
                dataType = DataType.UNKNOWN;
            }
        }else{
            s = field.getSchema();
            if(dataType == DataType.UNKNOWN ){
                if(o instanceof Map) dataType = DataType.MAP;
                if(o instanceof List) dataType = DataType.BAG;
            }
        }
        
        if(dataType == DataType.BYTEARRAY && o instanceof Map){
            dataType = DataType.MAP;
        }

        switch (dataType) {
            case DataType.NULL:
                return null;
            case DataType.INTEGER:
            case DataType.LONG:
            case DataType.FLOAT:
            case DataType.DOUBLE:
                return o;
            case DataType.BYTEARRAY:
                return o.toString();
            case DataType.CHARARRAY:
                String carray = (String)o;
                if(carray!=null && carray.startsWith("ISODate(") && carray.endsWith(")")){
                    String b = carray.substring(8, carray.indexOf(")"));
                    SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'");
                    sdf.setTimeZone(TimeZone.getTimeZone("GMT+0:00"));
                    try {
                        return sdf.parse(b);
                    } catch (ParseException e) {
                        e.printStackTrace();
                    }
                }
                else if(carray!=null && carray.startsWith("ObjectId(") && carray.endsWith(")")){
                    String b = carray.substring(9, carray.indexOf(")"));
                    return new ObjectId(b);
                }
                return carray;
            //Given a TUPLE, create a Map so BSONEncoder will eat it
            case DataType.TUPLE:
                if (s == null) {
                    throw new IOException("Schemas must be fully specified to use "
                                          + "this storage function.  No schema found for field " +
                      field.getName());
                }
                ResourceSchema.ResourceFieldSchema[] fs = s.getFields();
                LinkedHashMap m = new java.util.LinkedHashMap();
                for (int j = 0; j < fs.length; j++) {
                    m.put(fs[j].getName(), getTypeForBSON(((Tuple) o).get(j), fs[j], toIgnore));
                }
                return m;
                
                // Given a BAG, create an Array so BSONEnconder will eat it.
            case DataType.BAG:
                if (s == null) {
                    throw new IOException("Schemas must be fully specified to use "
                                          + "this storage function.  No schema found for field " +
                                          field.getName());
                }
                fs = s.getFields();
                if (fs.length != 1 || fs[0].getType() != DataType.TUPLE) {
                    throw new IOException("Found a bag without a tuple "
                                          + "inside!");
                }
                // Drill down the next level to the tuple's schema.
                s = fs[0].getSchema();
                if (s == null) {
                    throw new IOException("Schemas must be fully specified to use "
                                          + "this storage function.  No schema found for field " +
                                          field.getName());
                }
                fs = s.getFields();
                ArrayList<Object> a = new ArrayList<Object>();
                
                // check if fs[0] should be 'unnamed', in which case, we create an array
                // of 'inner' elements.
                // For example, {("a"),("b")} becomes ["a","b"] if
                // unnamedStr == "t" and schema for bag is {<*>:(t:chararray)}
                // <*> -> can be any string since the field name of the tuple in a bag should be ignored 
                if (fs.length == 1 && fs[0].getName().equals(toIgnore)) {
                    for (Tuple t : (DataBag) o) {
                        a.add(t.get(0));
                    }
                } else {
                    for (Tuple t : (DataBag)o) {
                        LinkedHashMap ma = new java.util.LinkedHashMap();
                        for (int j = 0; j < fs.length; j++) {
                            ma.put(fs[j].getName(), getTypeForBSON(t.get(j), fs[j], toIgnore));
                        }
                        a.add(ma);
                    }
                }
                
                return a;
            case DataType.MAP:
                Map map = (Map) o;
                Map<String,Object> out = new HashMap<String,Object>(map.size());
                for(Object key : map.keySet()) {
                    out.put(key.toString(), getTypeForBSON(map.get(key), null, toIgnore));
                }
                return out;
            default:
                return o;
        }
    }
    
    protected void writeField(BasicDBObjectBuilder builder,
                              ResourceSchema.ResourceFieldSchema field,
                              Object d) throws IOException {
        Object convertedType = getTypeForBSON(d, field, null);
        String fieldName = field != null ? field.getName() : "value";
        
        if(convertedType instanceof Map){
            for( Map.Entry<String, Object> mapentry : ((Map<String,Object>)convertedType).entrySet() ){
                String addKey = mapentry.getKey().equals(this.idField) ? "_id" : mapentry.getKey();
                builder.add(addKey, mapentry.getValue());
            }
        }else{
            String addKey =  field!=null && fieldName.equals(this.idField) ? "_id" : fieldName;
            builder.add(fieldName, convertedType);
        }
        
    }
    
    public void checkSchema( ResourceSchema schema ) throws IOException{
        this.schema = schema;
        UDFContext udfc = UDFContext.getUDFContext();
        
        Properties p = udfc.getUDFProperties(this.getClass(), new String[]{udfcSignature});
        p.setProperty(SCHEMA_SIGNATURE, schema.toString());
    }
    
    public void storeSchema( ResourceSchema schema, String location, Job job ){
        // not implemented
    }
    
    
    public void storeStatistics( ResourceStatistics stats, String location, Job job ){
        // not implemented
    }
    
    public void putNext( Tuple tuple ) throws IOException{
        try{
            final BasicDBObjectBuilder builder = BasicDBObjectBuilder.start();
            ResourceFieldSchema[] fields = null;
            if(this.schema != null){
                fields = this.schema.getFields();
            }
            if(fields != null){
                for (int i = 0; i < fields.length; i++) {
                    writeField(builder, fields[i], tuple.get(i));
                }
            }else{
                for (int i = 0; i < tuple.size(); i++) {
                    writeField(builder, null, tuple.get(i));
                }
            }
            
            BSONObject bsonformat = builder.get();
            this.out.write(null, bsonformat);
        }catch(Exception e){
            throw new IOException("Couldn't convert tuple to bson: " , e);
        }
    }
    
    public void prepareToWrite( RecordWriter writer ) throws IOException{
        this.out = writer;
        if ( this.out == null )
            throw new IOException( "Invalid Record Writer" );
        
        UDFContext udfc = UDFContext.getUDFContext();
        Properties p = udfc.getUDFProperties(this.getClass(), new String[]{udfcSignature});
        String strSchema = p.getProperty(SCHEMA_SIGNATURE);
        if (strSchema == null) {
            log.warn("Could not find schema in UDF context!");
            log.warn("Will attempt to write records without schema.");
        }
        
        try {
            // Parse the schema from the string stored in the properties object.
            this.schema = new ResourceSchema(Utils.getSchemaFromString(strSchema));
        } catch (Exception e) {
            this.schema = null;
            log.warn(e.getMessage());
        }
        
    }
    
    public OutputFormat getOutputFormat() throws IOException{
        return this.outputFormat;
    }
    
    public String relToAbsPathForStoreLocation( String location, org.apache.hadoop.fs.Path curDir ) throws IOException{
        return LoadFunc.getAbsolutePath(location, curDir);
    }
    
    public void setStoreLocation( String location, Job job ) throws IOException{
        final Configuration config = job.getConfiguration();
        config.set("mapred.output.file", location);
    }
    

    @Override
    public void setStoreFuncUDFContextSignature(String signature) {
        udfcSignature = signature;
    }
    
}
