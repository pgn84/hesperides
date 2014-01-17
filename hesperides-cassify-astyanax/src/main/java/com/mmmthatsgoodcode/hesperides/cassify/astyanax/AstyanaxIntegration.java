package com.mmmthatsgoodcode.hesperides.cassify.astyanax;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import com.mmmthatsgoodcode.astyanax.DynamicCompositeRangeBuilder;
import com.mmmthatsgoodcode.hesperides.cassify.HesperidesRowTransformer;
import com.mmmthatsgoodcode.hesperides.cassify.integration.CassandraThriftClientException;
import com.mmmthatsgoodcode.hesperides.cassify.integration.CassandraThriftClientIntegration;
import com.mmmthatsgoodcode.hesperides.cassify.integration.NodeLocator;
import com.mmmthatsgoodcode.hesperides.cassify.model.HesperidesColumn;
import com.mmmthatsgoodcode.hesperides.cassify.model.HesperidesColumn.AbstractType;
import com.mmmthatsgoodcode.hesperides.cassify.model.HesperidesColumn.ByteValue;
import com.mmmthatsgoodcode.hesperides.cassify.model.HesperidesRow;
import com.mmmthatsgoodcode.hesperides.core.AbstractSerializer;
import com.mmmthatsgoodcode.hesperides.core.TransformationException;
import com.netflix.astyanax.AstyanaxContext;
import com.netflix.astyanax.ColumnListMutation;
import com.netflix.astyanax.Keyspace;
import com.netflix.astyanax.MutationBatch;
import com.netflix.astyanax.connectionpool.OperationResult;
import com.netflix.astyanax.connectionpool.exceptions.ConnectionException;
import com.netflix.astyanax.model.ColumnFamily;
import com.netflix.astyanax.model.ColumnList;
import com.netflix.astyanax.serializers.BytesArraySerializer;
import com.netflix.astyanax.serializers.CompositeRangeBuilder;
import com.netflix.astyanax.serializers.StringSerializer;

public class AstyanaxIntegration implements CassandraThriftClientIntegration {

	private final AstyanaxContext<Keyspace> keyspaceContext;
	private AstyanaxCassifier cassifier = new AstyanaxCassifier();
	
	public AstyanaxIntegration(AstyanaxContext<Keyspace> keyspaceContext) {
		this.keyspaceContext = keyspaceContext;
	}
	
	@Override
	public void store(String cfName, HesperidesRow row) throws CassandraThriftClientException {
		
		ColumnFamily columnFamily = new ColumnFamily<byte[], HesperidesDynamicComposite>(cfName, BytesArraySerializer.get(), HesperidesDynamicCompositeSerializer.get());
		ColumnFamily indexColumnFamily = new ColumnFamily<byte[], HesperidesDynamicComposite>(cfName+INDEX_CF_SUFFIX, BytesArraySerializer.get(), HesperidesDynamicCompositeSerializer.get());
		ColumnFamily indexCacheColumnFamily = new ColumnFamily<byte[], HesperidesDynamicComposite>(cfName+INDEX_CACHE_CF_SUFFIX, BytesArraySerializer.get(), HesperidesDynamicCompositeSerializer.get());
		
		// first, find indexed rows
		List<HesperidesColumn> indexes = CassandraThriftClientIntegration.IndexedRows.find(row);
		
		MutationBatch mutationBatch = keyspaceContext.getClient().prepareMutationBatch();
		MutationBatch indexMutationBatch = keyspaceContext.getClient().prepareMutationBatch();
		MutationBatch indexCacheMutationBatch = keyspaceContext.getClient().prepareMutationBatch();

		/* 1) Mutation on the index CF for any indexed columns
		----------------------------------------------------------- */
		
		for (HesperidesColumn index:indexes) {
		    		
			/* save Index row
			 * - row key is index name components joined by COMPONENT_DELIMITER + the index value
			 * - column name is indexed row's key
			------------- */
			
			// create the name for the index row			

			
			// TODO hash name before writing it
			HesperidesRow indexRow = new HesperidesRow(indexRowKey(index.getNameComponents(), index.getValue().getSerializer().toByteBuffer(index.getValue().getValue()).array()));
			HesperidesColumn indexColumn = new HesperidesColumn();
			indexColumn.addNameComponent(new HesperidesColumn.ByteValue(row.getKey()));
			indexColumn.setCreated(index.getCreated());
			indexColumn.setTtl(index.getTtl());
			
			indexRow.addColumn(indexColumn);
			
			ColumnListMutation<HesperidesDynamicComposite> indexMutation = indexMutationBatch.withRow(indexColumnFamily, indexRow.getKey());
			cassifier.populateColumnListMutation( indexMutation, indexRow );
			
			/* save IndexCache row
			 * - row key is index name
			 * - column name is composite of index value and indexed row's key
			------------------ */
			
			// create the name for the index row
						
			HesperidesRow indexCacheRow = new HesperidesRow(indexCacheRowKey(index.getNameComponents()));
			HesperidesColumn indexCacheColumn = new HesperidesColumn();
			indexCacheColumn.addNameComponent(index.getValue());
			indexCacheColumn.addNameComponent(new HesperidesColumn.ByteValue( row.getKey() ));
			indexCacheColumn.setCreated(index.getCreated());
			indexCacheColumn.setTtl(index.getTtl()); // TODO enforce a short TTL ( as this row could get quite wide )
			indexCacheRow.addColumn(indexCacheColumn);
			
			ColumnListMutation<HesperidesDynamicComposite> indexCacheMutation = indexCacheMutationBatch.withRow(indexCacheColumnFamily, indexCacheRow.getKey());

			cassifier.populateColumnListMutation( indexCacheMutation, indexCacheRow);
			
		}
		
		/* 2) Actual mutation for the row
		------------------------------------------ */

		ColumnListMutation<HesperidesDynamicComposite> mutation = mutationBatch.withRow(columnFamily, row.getKey());
		cassifier.populateColumnListMutation( mutation, row );

		
		try {
		    indexCacheMutationBatch.execute();
		    indexMutationBatch.execute();
		    mutationBatch.execute();
		} catch (ConnectionException e) {
			throw new CassandraThriftClientException(e);
		}
		
	}

	@Override
	public HesperidesRow retrieve(String cfName, byte[] rowKey) throws CassandraThriftClientException, TransformationException {
	    
		ColumnFamily columnFamily = new ColumnFamily<byte[], HesperidesDynamicComposite>(cfName, BytesArraySerializer.get(), HesperidesDynamicCompositeSerializer.get());

	    	try {
	    	    
		    OperationResult<ColumnList<HesperidesDynamicComposite>> results = keyspaceContext.getClient().prepareQuery(columnFamily).getKey(rowKey).execute();
		    return cassifier.cassify(results, rowKey);
		    
	    	} catch (ConnectionException e) {
	    	    
		    throw new CassandraThriftClientException(e);
		}

	}

	@Override
	public HesperidesRow retrieve(String cfName, byte[] rowKey, NodeLocator locator) throws CassandraThriftClientException {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public List<HesperidesRow> retrieve(String cfName, NodeLocator indexName, Object indexValue, int limit) throws CassandraThriftClientException, TransformationException {

	    List<HesperidesRow> indexedRows = new ArrayList<HesperidesRow>();
	    ColumnFamily indexCacheColumnFamily = new ColumnFamily<byte[], HesperidesDynamicComposite>(cfName+INDEX_CACHE_CF_SUFFIX, BytesArraySerializer.get(), HesperidesDynamicCompositeSerializer.get());
	    // figure out the indexCache row key
	    byte[] indexCacheRowKey = indexCacheRowKey(indexName.components());

	    OperationResult<ColumnList<HesperidesDynamicComposite>> results;
	    
	    try {
        		
                // look in indexCache CF
		DynamicCompositeRangeBuilder indexCacheRangeBuilder = HesperidesDynamicCompositeSerializer.get()
			.buildRange(HesperidesDynamicComposite.serializerToAliasMapping())
//			.withPrefix(indexValue)
			.beginsWith(indexValue);
//			.lessThanEquals(indexValue);
//			.lessThanEquals(Integer.MAX_VALUE);
//			.lessThanEquals(0);
		
//		System.out.println("--- "+indexCacheRangeBuilder.build());
		
		
		results = keyspaceContext.getClient()
			.prepareQuery(indexCacheColumnFamily)
			.getKey(indexCacheRowKey)
			.withColumnRange(indexCacheRangeBuilder).execute();
                	    
                // see if we have a result
                if (results.getResult().isEmpty() == false) {
                    HesperidesRow indexCacheRow = cassifier.cassify(results, indexCacheRowKey);
                    // TODO apply limit
                    for (HesperidesColumn indexCacheColumn:indexCacheRow.getColumns()) {
                	AbstractType rowKey = indexCacheColumn.getNameComponents().get(1);
                	
                	
                	if (rowKey instanceof ByteValue) {
                	    indexedRows.add(retrieve(cfName, ((ByteValue)rowKey).getValue()));
                	} else {
                	    
                	}
                	
                	
                    }

                }
    
    	    
                // nope, look in the index CF
                ColumnFamily indexColumnFamily = new ColumnFamily<byte[], HesperidesDynamicComposite>(cfName+INDEX_CF_SUFFIX, BytesArraySerializer.get(), HesperidesDynamicCompositeSerializer.get());
                byte[] indexRowKey = indexRowKey(indexName.components(), indexValue);

		results = keyspaceContext.getClient()
	                    .prepareQuery(indexColumnFamily)
	                    .getKey(indexRowKey)
	                    .execute();
	        
                // see if we have a result
                if (results.getResult().isEmpty() == false) {
//                    indexedRows cassifier.cassify(results, indexRowKey);
                }
		
	    } catch (ConnectionException e) {
        
		throw new CassandraThriftClientException(e);
	    }
        	
	    return indexedRows;
	}

	@Override
	public List<HesperidesRow> retrieve(String cfName, NodeLocator indexName, Object indexValue, int limit,
			NodeLocator locator) throws CassandraThriftClientException {
		// TODO Auto-generated method stub
		return null;
	}


	@Override
	public List<String> retrieveRowKeysByIndex(String cfName, NodeLocator indexName,
			byte[] indexValue, int limit) {
		// TODO Auto-generated method stub
		return null;
	}
	
	/**
	 * Constructs the row key used in the index CF
	 * @param indexName
	 * @param indexValue
	 * @return
	 */
	private byte[] indexRowKey(List<AbstractType> indexName, Object indexValue) {
	    
	    ByteArrayOutputStream name = new ByteArrayOutputStream();
	    
        	try {
        
        	    // serialise name components to bytes
        	    for (AbstractType nameComponent : indexName) {
        		name.write(nameComponent.getSerializer().toByteBuffer(nameComponent.getValue()).array());
        		name.write(COMPONENT_DELIMITER);
        	    }
        
        	    
        	    // add value to name
        	    name.write(AbstractSerializer.infer(indexValue).toByteBuffer(indexValue).array());
        
        	} catch (IOException e) {
        	    // not going to happen
        
        	}
            	
            return name.toByteArray();
	    
	}
	
	/**
	 * Constructs the row key used in the indexCache CF
	 * @param indexName
	 * @return
	 */
	private byte[] indexCacheRowKey(List<AbstractType> indexName) {
	    
	    ByteArrayOutputStream name = new ByteArrayOutputStream();

        	try {
        
        	    // serialise name components to bytes
        	    for (AbstractType nameComponent : indexName) {
        		name.write(nameComponent.getSerializer().toByteBuffer(nameComponent.getValue()).array());
        		name.write(COMPONENT_DELIMITER);
        	    }
        
        	} catch (IOException e) {
        	    // not going to happen
        
        	}
            	
            return name.toByteArray();
	    
	}
	
	

}
