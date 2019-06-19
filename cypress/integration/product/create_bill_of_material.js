import { Product, ProductCategory } from '../../support/utils/product';
import { BillOfMaterial } from '../../support/utils/billOfMaterial';

describe('Create Product', function() {
  const timestamp = new Date().getTime();
  const productName = `ProductName ${timestamp}`;
  const productValue = `ProductNameValue ${timestamp}`;
  const productCategoryName = `ProductCategoryName ${timestamp}`;
  const productCategoryValue = `ProductNameValue ${timestamp}`;
  const productComponentName = `ProductComponentName ${timestamp}`;
  const productComponentValue = `ProductComponentValue ${timestamp}`;

  it('Create a new ProductCategory', function() {
    cy.fixture('product/simple_productCategory.json').then(productCategoryJson => {
      Object.assign(new ProductCategory(), productCategoryJson)
        .setName(productCategoryName)
        .setValue(productCategoryValue)
        .apply();
    });
  });

  it('Create Product', function() {
    cy.fixture('product/simple_product.json').then(productJson => {
      Object.assign(new Product(), productJson)
        .setName(productName)
        .setValue(productValue)
        .setProductCategory(productCategoryValue + '_' + productCategoryName)
        .setStocked(false)
        .setPurchased(false)
        .setSold(false)
        .apply();
    });
  });

  it('Create Product', function() {
    cy.fixture('product/simple_product.json').then(productJson => {
      Object.assign(new Product(), productJson)
        .setName(productComponentName)
        .setValue(productComponentValue)
        .setProductCategory(productCategoryValue + '_' + productCategoryName)
        .setStocked(false)
        .setPurchased(false)
        .setSold(false)
        .apply();
    });
  });

  it('Create a new Bill of Material', function() {
    cy.fixture('product/bill_of_material.json').then(billMaterialJson => {
      Object.assign(new BillOfMaterial(), billMaterialJson)
        .setProduct(productName)
        .setProductComponent(productComponentName)
        .apply();
    });
    cy.visitWindow('140');
  });
});
